package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.compactItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
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
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A tap on a song plays the whole list from it, through the playback service (docs/playback.md §2.3). */
@RunWith(AndroidJUnit4::class)
class PlaybackQueueTest {

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

    @Test
    fun aTappedSongPlaysAndTheNextOneFollows() {
        songs.songs = listOf(SongSourceRule.song(2, "Bravo"), SongSourceRule.song(1, "Alpha"),
            SongSourceRule.song(3, "Charlie"))

        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Alpha")

        controller.waitUntil("Alpha plays as the first of three") {
            isPlaying && currentMediaItem?.mediaId == "1" && mediaItemCount == 3
        }
        assertThat(onMain { controller.mediaMetadata.title.toString() }).isEqualTo("Alpha")
        controller.waitUntil("Bravo follows", timeout = 10.seconds) { isPlaying && currentMediaItem?.mediaId == "2" }
    }

    @Test
    fun thePlatformSessionShowsTheSongButNotTheQueue() {
        songs.songs = listOf(SongSourceRule.song(1, "Alpha", PlaybackTesting.LONG_FIXTURE),
            SongSourceRule.song(2, "Bravo"), SongSourceRule.song(3, "Charlie"))
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Alpha")
        controller.waitUntil("Alpha plays") { isPlaying && currentMediaItem?.mediaId == "1" && mediaItemCount == 3 }

        val context = TestFiles.targetContext
        val connected = CountDownLatch(1)
        val browser = onMain {
            MediaBrowser(context, ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                }, null).also { it.connect() }
        }
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
            val platform = onMain { android.media.session.MediaController(context, browser.sessionToken) }
            eventually("the platform session shows Alpha") {
                platform.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == "Alpha"
            }
            assertThat(platform.queue.orEmpty()).isEmpty()
        } finally {
            onMain { browser.disconnect() }
        }
    }

    @Test
    fun aQueueOf20000SongsStartsWithinASecond() {
        val template = SongSourceRule.song(0, "Song")
        songs.songs = List(SIZE) { index ->
            template.copy(id = index + 1L, title = "Song %05d of a long list".format(index + 1), artist = "Artist ${index % 500}",
                album = "Album ${index % 2_000}")
        }
        // The list screen has already read the list when a song is tapped.
        SongList.load(TestFiles.targetContext)
        val start = QueueRequest(startSongId = SIZE / 2L, startRow = SIZE / 2 - 1)

        val times = List(1 + RUNS) { run ->
            val started = AtomicLong()
            val timelineAt = AtomicLong()
            val done = CountDownLatch(1)
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (timelineAt.get() == 0L && player.currentTimeline.windowCount == SIZE) {
                        timelineAt.set(SystemClock.elapsedRealtimeNanos())
                    }
                    if (player.isPlaying && player.currentTimeline.windowCount == SIZE &&
                        player.currentMediaItem?.mediaId == start.startSongId.toString()
                    ) {
                        started.set(SystemClock.elapsedRealtimeNanos())
                        done.countDown()
                    }
                }
            }
            onMain { controller.addListener(listener) }
            val t0 = onMain {
                val t = SystemClock.elapsedRealtimeNanos()
                controller.setMediaItem(compactItem(start))
                controller.prepare()
                controller.play()
                t
            }
            assertThat(done.await(20, TimeUnit.SECONDS)).describedAs("run $run started").isTrue()
            onMain {
                controller.removeListener(listener)
                controller.stop()
                controller.clearMediaItems()
            }
            controller.waitUntil("the queue is empty") { mediaItemCount == 0 }
            ((started.get() - t0) / 1_000_000).also { Log.i(TAG, "run $run: $it ms, timeline at ${(timelineAt.get() - t0) / 1_000_000} ms") }
        }

        val measured = times.drop(1).sorted()
        val median = measured[measured.size / 2]
        Log.i(TAG, "20 000 songs: warm-up ${times.first()} ms, measured $measured ms, median $median ms")
        // The owner's decision of 20.10: the emulator image with 16 KB pages is slower throughout (1.3 to 1.9 s here),
        // so only the time is logged there; the bound holds on the phone and the API 30 and 36 emulators (§2.5).
        assumeFalse("the 16 KB page-size emulator only logs the time", isEmulatorWith16KbPages())
        assertThat(median).describedAs("median of $measured ms").isLessThanOrEqualTo(1_000L)
    }

    private fun isEmulatorWith16KbPages() =
        Build.HARDWARE == "ranchu" && Os.sysconf(OsConstants._SC_PAGESIZE) == 16_384L

    private companion object {
        const val TAG = "PlaybackQueueTest"
        const val SIZE = 20_000
        const val RUNS = 5
    }
}
