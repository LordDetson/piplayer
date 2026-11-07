package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.TimingTest
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An edit of a 20 000-song queue reaches the controllers within 0.25 s (docs/playback.md §9.5). */
@RunWith(AndroidJUnit4::class)
class QueueEditTimingTest {

    @get:Rule
    val songs = SongSourceRule()

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun send(command: SessionCommand, args: Bundle.() -> Unit) {
        val result = onMain { controller.sendCustomCommand(command, Bundle().apply(args)) }.get(10, TimeUnit.SECONDS)
        assertThat(result.resultCode).describedAs(command.customAction).isEqualTo(SessionResult.RESULT_SUCCESS)
    }

    /** Milliseconds from [edit] until the controller's timeline [done]. */
    private fun timed(edit: () -> Unit, done: (Player) -> Boolean): Long {
        val at = AtomicLong()
        val latch = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (at.get() == 0L && done(player)) {
                    at.set(SystemClock.elapsedRealtimeNanos())
                    latch.countDown()
                }
            }
        }
        onMain { controller.addListener(listener) }
        val start = SystemClock.elapsedRealtimeNanos()
        edit()
        assertThat(latch.await(10, TimeUnit.SECONDS)).describedAs("the edit arrived").isTrue()
        onMain { controller.removeListener(listener) }
        return (at.get() - start) / 1_000_000
    }

    @Test
    @TimingTest
    fun editsOfA20000SongQueueArriveWithinAQuarterOfASecond() {
        val template = SongSourceRule.song(0, "Song")
        songs.songs = List(SIZE) { index -> template.copy(id = index + 1L, title = "Song %05d".format(index + 1)) }
        SongList.load(TestFiles.targetContext)
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Song 00001")
        controller.waitUntil("the queue plays", timeout = 30.seconds) { mediaItemCount == SIZE && isPlaying }

        val adds = mutableListOf<Long>()
        val removals = mutableListOf<Long>()
        val moves = mutableListOf<Long>()
        repeat(1 + RUNS) { run ->
            adds += timed({
                send(PlaybackSessionCallback.ADD_TO_QUEUE_COMMAND) {
                    putLongArray(PlaybackSessionCallback.EXTRA_SONG_IDS, longArrayOf(SIZE / 2L))
                    putBoolean(PlaybackSessionCallback.EXTRA_NEXT, true)
                }
            }) { it.currentTimeline.windowCount == SIZE + 1 }
            val added = onMain { controller.getMediaItemAt(1).mediaId }
            removals += timed({
                send(PlaybackSessionCallback.REMOVE_FROM_QUEUE_COMMAND) {
                    putInt(PlaybackSessionCallback.EXTRA_ROW, 1)
                    putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, added)
                }
            }) { it.currentTimeline.windowCount == SIZE }
            val moved = onMain { controller.getMediaItemAt(10).mediaId }
            moves += timed({
                send(PlaybackSessionCallback.MOVE_IN_QUEUE_COMMAND) {
                    putInt(PlaybackSessionCallback.EXTRA_FROM, 10)
                    putInt(PlaybackSessionCallback.EXTRA_TO, 5_000 + run)
                    putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, moved)
                }
            }) { it.currentTimeline.windowCount == SIZE && it.getMediaItemAt(5_000 + run).mediaId == moved }
        }

        for ((name, times) in listOf("add" to adds, "remove" to removals, "move" to moves)) {
            val measured = times.drop(1).sorted()
            val median = measured[measured.size / 2]
            Log.i(TAG, "$name in 20 000: warm-up ${times.first()} ms, measured $measured ms, median $median ms")
            assertThat(median).describedAs("$name: median of $measured ms").isLessThanOrEqualTo(250L)
        }
    }

    private companion object {
        const val TAG = "QueueEditTimingTest"
        const val SIZE = 20_000
        const val RUNS = 5
    }
}
