package io.github.lorddetson.piplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The play counter on the service's real player (docs/playback.md §11). The clock is the test's: the player really
 * plays, and the test moves the listened time on while it does. The fixtures are short, so the threshold is the test's
 * too; its rule has the JVM tests of `PlayCounter`.
 */
@RunWith(AndroidJUnit4::class)
class PlayTrackerTest {

    private class Recorded(val kind: String, val mediaId: String, val startedAt: Long, val listenedMs: Long)

    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val recorder = object : PlayRecorder {
        override fun counted(mediaId: String, startedAt: Long, listenedMs: Long) {
            recorded += Recorded("counted", mediaId, startedAt, listenedMs)
        }

        override fun ended(mediaId: String, startedAt: Long, listenedMs: Long) {
            recorded += Recorded("ended", mediaId, startedAt, listenedMs)
        }
    }

    @Volatile
    private var now = 10_000L
    private var wall = 1_800_000_000_000L
    private lateinit var player: ExoPlayer
    private lateinit var tracker: PlayTracker

    @After
    fun release() {
        onMain {
            tracker.release()
            player.release()
        }
    }

    private fun item(id: String, asset: String = PlaybackTesting.LONG_FIXTURE): MediaItem =
        fileItem(asset).buildUpon().setMediaId(id).build()

    private fun start(vararg items: MediaItem, repeat: Int = Player.REPEAT_MODE_OFF, listener: Player.Listener? = null) {
        onMain {
            player = PlaybackPlayer.builder(TestFiles.targetContext).build()
            listener?.let(player::addListener)
            tracker = PlayTracker(player, recorder, clock = { now }, wallClock = { wall }, thresholdOf = { 3_000L })
            tracker.start()
            player.repeatMode = repeat
            player.setMediaItems(items.toList())
            player.prepare()
            player.play()
        }
        awaitPlayer("it plays") { isPlaying }
    }

    /** Waits, by the player's own reports, until [condition] holds on the main thread. */
    private fun awaitPlayer(what: String, condition: Player.() -> Boolean) {
        val reached = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.condition()) reached.countDown()
            }
        }
        onMain {
            player.addListener(listener)
            if (player.condition()) reached.countDown()
        }
        try {
            check(reached.await(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)) { "The player never reached: $what" }
        } finally {
            onMain { player.removeListener(listener) }
        }
    }

    private fun listen(ms: Long) = onMain {
        now += ms
        tracker.checkNow()
    }

    private fun counted() = recorded.filter { it.kind == "counted" }.map { it.mediaId }

    @Test
    fun aPlaybackCountsOnceItsListenedTimeReachesTheThreshold() {
        start(item("a"))

        listen(2_999)
        assertThat(counted()).isEmpty()
        listen(1)
        listen(10_000)

        assertThat(counted()).containsExactly("a")
        assertThat(recorded.single().startedAt).isEqualTo(wall)
        assertThat(onMain { tracker.progress() }).isEqualTo(ListenProgress(13_000, counted = true, startedAt = wall))
    }

    @Test
    fun timeWhilePausedDoesNotCount() {
        start(item("a"))
        listen(1_000)
        onMain { player.pause() }
        awaitPlayer("it pauses") { !isPlaying }

        listen(60_000)

        assertThat(counted()).isEmpty()
        assertThat(onMain { tracker.progress()?.listenedMs }).isEqualTo(1_000)
    }

    @Test
    fun aSeekInsideTheSongContinuesItsPlaybackAndAddsNothing() {
        start(item("a"))
        listen(1_000)

        onMain { player.seekTo(9_000) }
        awaitPlayer("it plays after the seek") { isPlaying && currentPosition >= 9_000 }

        assertThat(recorded).isEmpty()
        assertThat(onMain { tracker.progress()?.listenedMs }).isEqualTo(1_000)
    }

    @Test
    fun theNextSongIsANewPlaybackAndTheCountedOneEndsWithItsListenedTime() {
        start(item("a"), item("b"))
        listen(4_000)

        onMain { player.seekToNextMediaItem() }
        awaitPlayer("b plays") { currentMediaItem?.mediaId == "b" && isPlaying }
        listen(3_000)

        assertThat(recorded.map { it.kind to it.mediaId })
            .containsExactly("counted" to "a", "ended" to "a", "counted" to "b")
        assertThat(recorded[1].listenedMs).isEqualTo(4_000)
    }

    @Test
    fun aRepeatOneReplayCountsAgain() {
        // The 2 s song ends and plays again; the first playback counts long before it ends.
        val replayed = CountDownLatch(1)
        val repeats = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) replayed.countDown()
            }
        }
        start(item("a", PlaybackTesting.SHORT_FIXTURE), repeat = Player.REPEAT_MODE_ONE, listener = repeats)
        listen(3_000)
        assertThat(counted().first()).isEqualTo("a")

        check(replayed.await(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)) { "No repeat came" }
        listen(3_000)

        assertThat(counted()).containsExactly("a", "a")
    }

    @Test
    fun aChangeOfTheQueueThatKeepsTheSongKeepsItsPlayback() {
        start(item("a"))
        listen(1_000)

        onMain { player.addMediaItem(item("b")) }
        awaitPlayer("the queue has two songs") { mediaItemCount == 2 }
        listen(2_000)

        assertThat(counted()).containsExactly("a")
        assertThat(recorded.none { it.kind == "ended" }).isTrue()
    }

    @Test
    fun aResumedItemGoesOnFromItsSavedProgress() {
        onMain {
            player = PlaybackPlayer.builder(TestFiles.targetContext).build()
            tracker = PlayTracker(player, recorder, clock = { now }, wallClock = { wall }, thresholdOf = { 3_000L })
            tracker.start()
            tracker.resume("a", ListenProgress(listenedMs = 2_500, counted = false, startedAt = 42))
            player.setMediaItem(item("a"))
            player.prepare()
            player.play()
        }
        awaitPlayer("it plays") { isPlaying }

        listen(500)

        assertThat(counted()).containsExactly("a")
        assertThat(recorded.single().startedAt).isEqualTo(42)
    }

    private companion object {
        /** Far more than any of these waits needs; a wait ends at the event, not at this. */
        const val SAFETY_TIMEOUT_S = 30L
    }
}
