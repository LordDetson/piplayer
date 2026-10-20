package io.github.lorddetson.piplayer.playback

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shuffle, repeat and previous through the playback service (docs/playback.md §3.6). */
@RunWith(AndroidJUnit4::class)
class QueueNavigationTest {

    /** Ten songs whose rows in title order are their IDs minus one. */
    @get:Rule
    val songs = SongSourceRule {
        List(10) { SongSourceRule.song(it + 1L, "Song %02d".format(it + 1), PlaybackTesting.LONG_FIXTURE) }
    }

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    /** The songs in the order shuffle plays them, as the controller's timeline has it. */
    private fun MediaController.shuffleOrder(): List<Int> = onMain {
        val timeline = currentTimeline
        generateSequence(timeline.getFirstWindowIndex(true).takeIf { it != C.INDEX_UNSET }) { index ->
            timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true).takeIf { it != C.INDEX_UNSET }
        }.take(timeline.windowCount + 1).toList()
    }

    private fun playRow(row: Int) {
        controller.tap(QueueRequest(startSongId = row + 1L, startRow = row))
        controller.waitUntil("row $row plays") { isPlaying && currentMediaItemIndex == row && mediaItemCount == 10 }
    }

    /**
     * Moves to [row] of a queue that started elsewhere: a new queue's order already starts at its start song, so only
     * a song moved to shows that turning shuffle on starts the order at the current song.
     */
    private fun moveTo(row: Int, positionMs: Long = 0) {
        onMain { controller.seekTo(row, positionMs) }
        controller.waitUntil("row $row plays from $positionMs ms") {
            currentMediaItemIndex == row && currentPosition >= positionMs
        }
    }

    private fun turnShuffleOnAt(row: Int) {
        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle starts at row $row") {
            shuffleModeEnabled && currentTimeline.getFirstWindowIndex(true) == row
        }
    }

    @Test
    fun aSongTappedWithShuffleOnPlaysFirstAndEveryOtherSongOnceAfterIt() {
        onMain { controller.shuffleModeEnabled = true }

        playRow(5)

        val order = controller.shuffleOrder()
        assertThat(order.first()).isEqualTo(5)
        assertThat(order).containsExactlyInAnyOrderElementsOf(0 until 10)
        assertThat(onMain { controller.nextMediaItemIndex }).isEqualTo(order[1])
    }

    @Test
    fun shuffleTurnedOnInTheMiddleOfTheQueueStartsAtTheCurrentSongAndKeepsItsPosition() {
        playRow(3)
        moveTo(6, positionMs = 5_000)

        turnShuffleOnAt(6)

        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(6)
        assertThat(onMain { controller.currentPosition }).isGreaterThanOrEqualTo(4_900)
        assertThat(controller.shuffleOrder()).containsExactlyInAnyOrderElementsOf(0 until 10)
    }

    @Test
    fun shuffleTurnedOffGoesOnInListOrder() {
        playRow(3)
        moveTo(6)
        turnShuffleOnAt(6)

        onMain { controller.shuffleModeEnabled = false }

        controller.waitUntil("shuffle is off") { !shuffleModeEnabled }
        assertThat(onMain { controller.nextMediaItemIndex }).isEqualTo(7)
    }

    @Test
    fun repeatAllWrapsToTheFirstSongOfTheSameOrder() {
        playRow(3)
        moveTo(6)
        turnShuffleOnAt(6)
        onMain { controller.repeatMode = Player.REPEAT_MODE_ALL }
        val order = controller.shuffleOrder()

        onMain { controller.seekTo(order.last(), 0) }

        controller.waitUntil("the last song of the order plays") { currentMediaItemIndex == order.last() }
        assertThat(onMain { controller.nextMediaItemIndex }).isEqualTo(6)
        assertThat(controller.shuffleOrder()).isEqualTo(order)
    }

    @Test
    fun previousRestartsTheSongAfterThreeSecondsAndOtherwiseGoesBack() {
        playRow(1)
        onMain { controller.seekTo(5_000) }
        controller.waitUntil("the song is at 5 s") { currentPosition >= 5_000 }

        onMain { controller.seekToPrevious() }
        controller.waitUntil("the song starts again") { currentMediaItemIndex == 1 && currentPosition < 2_000 }

        onMain { controller.seekToPrevious() }
        controller.waitUntil("the previous song plays") { currentMediaItemIndex == 0 }
    }

    @Test
    fun previousWithShuffleGoesBackAlongTheOrder() {
        playRow(3)
        moveTo(6)
        turnShuffleOnAt(6)
        val order = controller.shuffleOrder()
        onMain { controller.seekTo(order[1], 0) }
        controller.waitUntil("the second song of the order plays") { currentMediaItemIndex == order[1] }

        onMain { controller.seekToPrevious() }

        controller.waitUntil("the first song of the order plays") { currentMediaItemIndex == 6 }
    }

    @Test
    fun turningShuffleOnInAQueueOf20000SongsIsQuick() {
        val template = SongSourceRule.song(0, "Song")
        songs.songs = List(SIZE) { index -> template.copy(id = index + 1L, title = "Song %05d".format(index + 1)) }
        SongList.load(TestFiles.targetContext)
        val start = SIZE / 2 - 1
        controller.tap(QueueRequest(startSongId = start + 1L, startRow = start))
        controller.waitUntil("the long queue plays", timeout = 20.seconds) {
            isPlaying && mediaItemCount == SIZE && currentMediaItemIndex == start
        }

        val times = List(1 + RUNS) { run ->
            // Elsewhere than the start song, whose order the queue already has.
            val current = start + 100 * (run + 1)
            onMain { controller.seekTo(current, 0) }
            controller.waitUntil("row $current plays") { currentMediaItemIndex == current }
            val done = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.shuffleModeEnabled && player.currentTimeline.getFirstWindowIndex(true) == current) done.countDown()
                }
            }
            onMain { controller.addListener(listener) }
            val t0 = onMain {
                val t = SystemClock.elapsedRealtimeNanos()
                controller.shuffleModeEnabled = true
                t
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).describedAs("run $run shuffled").isTrue()
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
            onMain {
                controller.removeListener(listener)
                controller.shuffleModeEnabled = false
            }
            controller.waitUntil("shuffle is off") { !shuffleModeEnabled }
            ms.also { Log.i(TAG, "run $run: $it ms") }
        }

        val measured = times.drop(1).sorted()
        val median = measured[measured.size / 2]
        Log.i(TAG, "shuffle on in 20 000 songs: warm-up ${times.first()} ms, measured $measured ms, median $median ms")
        // As for the queue start (§2.5): the emulator image with 16 KB pages is slower throughout, so there the time
        // is only logged.
        assumeFalse("the 16 KB page-size emulator only logs the time", isEmulatorWith16KbPages())
        assertThat(median).describedAs("median of $measured ms").isLessThanOrEqualTo(250L)
    }

    private fun isEmulatorWith16KbPages() =
        Build.HARDWARE == "ranchu" && Os.sysconf(OsConstants._SC_PAGESIZE) == 16_384L

    private companion object {
        const val TAG = "QueueNavigationTest"
        const val SIZE = 20_000
        const val RUNS = 5
    }
}
