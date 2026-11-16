package io.github.lorddetson.piplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.userdata.ListenOutcome
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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

        override fun listened(mediaId: String, startedAt: Long, listenedMs: Long, durationMs: Long?, outcome: ListenOutcome) {
            outcomes += Outcome(mediaId, outcome, startedAt, listenedMs, durationMs)
        }
    }

    /** A listening's outcome (docs/playback.md §12), kept apart from the plays. */
    private data class Outcome(val mediaId: String, val outcome: ListenOutcome, val startedAt: Long, val listenedMs: Long, val durationMs: Long?)

    private val outcomes = CopyOnWriteArrayList<Outcome>()

    private fun outcomes() = outcomes.map { it.mediaId to it.outcome }

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
    fun aRestartIsANewPlaybackOfTheSameSong() {
        start(item("a"))
        listen(4_000)

        // A restore that moves from the end of the song back to its start (docs/playback.md §4.3.1).
        onMain { tracker.restart() }
        listen(2_999)

        assertThat(recorded.map { it.kind to it.mediaId }).containsExactly("counted" to "a", "ended" to "a")
        assertThat(recorded.last().listenedMs).isEqualTo(4_000)
        assertThat(onMain { tracker.progress() }?.listenedMs).isEqualTo(2_999)
        listen(1)
        assertThat(counted()).containsExactly("a", "a")
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
    fun aCountedPlaybackThatEndsTheQueueGetsItsListenedTimeAtTheEnd() {
        start(item("a"))
        listen(4_000)
        assertThat(counted()).containsExactly("a")

        // Repeat is off and nothing follows: the queue ends, and nothing else ends this playback before the process may go.
        onMain { player.seekTo(11_000) }
        awaitPlayer("the queue ends") { playbackState == Player.STATE_ENDED }

        assertThat(recorded.map { it.kind to it.mediaId }).containsExactly("counted" to "a", "ended" to "a")
        assertThat(recorded.last().listenedMs).isEqualTo(4_000)
        assertThat(recorded.last().startedAt).isEqualTo(wall)
    }

    @Test
    fun aSavedPlaybackThatTheRestoreDoesNotResumeEndsOnlyIfItCounted() {
        start(item("b"))

        onMain {
            tracker.endSaved("a", ListenProgress(listenedMs = 295_000, counted = true, startedAt = 42))
            tracker.endSaved("c", ListenProgress(listenedMs = 100_000, counted = false, startedAt = 43))
        }

        assertThat(recorded.map { it.kind to it.mediaId }).containsExactly("ended" to "a")
        assertThat(recorded.single().listenedMs).isEqualTo(295_000)
        assertThat(recorded.single().startedAt).isEqualTo(42)
        // The current playback is untouched.
        assertThat(onMain { tracker.progress() }?.listenedMs).isEqualTo(0)
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
    fun theCurrentItemReplacedInPlaceWithNewValuesKeepsItsPlayback() {
        start(item("a"))
        listen(1_000)

        // The refresh of docs/playback.md §2.6: the same entry with the values the tag pass has just read. The player
        // keeps the entry and reports no transition for it; should it ever report one, this playback must still go on.
        onMain {
            val refreshed = item("a").buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle("Read").build()).build()
            player.replaceMediaItem(0, refreshed)
        }
        awaitPlayer("the item shows its new title") { currentMediaItem?.mediaMetadata?.title == "Read" }
        listen(2_000)

        assertThat(counted()).containsExactly("a")
        assertThat(recorded.none { it.kind == "ended" }).isTrue()
    }

    @Test
    fun aQueueThatOnlyGrowsElsewhereKeepsThePlayback() {
        start(item("a"))
        listen(1_000)

        onMain { player.addMediaItem(item("b")) }
        awaitPlayer("the queue has two songs") { mediaItemCount == 2 }
        listen(2_000)

        assertThat(counted()).containsExactly("a")
        assertThat(recorded.none { it.kind == "ended" }).isTrue()
    }

    @Test
    fun theSameSongChosenAgainFromAListIsANewPlayback() {
        start(item("a"))
        listen(4_000)
        assertThat(counted()).containsExactly("a")

        // A list played from the same song: the session sets a new queue, as onSetMediaItems does.
        onMain { player.setMediaItems(listOf(item("a"), item("b")), 0, 0) }
        awaitPlayer("the new queue plays") { mediaItemCount == 2 && isPlaying }
        listen(3_000)

        assertThat(recorded.map { it.kind to it.mediaId })
            .containsExactly("counted" to "a", "ended" to "a", "counted" to "a")
        assertThat(recorded[1].listenedMs).isEqualTo(4_000)
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

    @Test
    fun nextBeforeThirtySecondsIsAnEarlySkipAndAfterThirtySecondsALateOne() {
        start(item("a"), item("b"), item("c"))
        listen(29_900)

        onMain { player.seekToNextMediaItem() }
        awaitPlayer("b plays") { currentMediaItem?.mediaId == "b" && isPlaying }
        listen(30_000)
        onMain { player.seekToNextMediaItem() }
        awaitPlayer("c plays") { currentMediaItem?.mediaId == "c" && isPlaying }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.EARLY_SKIP, "b" to ListenOutcome.LATE_SKIP)
        assertThat(outcomes.map { it.listenedMs }).containsExactly(29_900L, 30_000L)
        assertThat(outcomes[0].startedAt).isEqualTo(wall)
        // The player's length of the song, which the share of a late skip is taken of.
        assertThat(outcomes[1].durationMs).isNotNull()
        assertThat(outcomes[1].durationMs!!).isCloseTo(SongSourceRule.LONG_FIXTURE_MS, within(100L))
    }

    @Test
    fun aSongThatEndsIntoTheNextOneWasListenedToTheEnd() {
        start(item("a", PlaybackTesting.SHORT_FIXTURE), item("b"))
        listen(1_000)

        awaitPlayer("b follows") { currentMediaItem?.mediaId == "b" }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.FINISHED)
        assertThat(outcomes.single().listenedMs).isEqualTo(1_000)
    }

    @Test
    fun aRepeatOneReplayEndsThePlaybackBeforeAsListenedToTheEnd() {
        val replayed = CountDownLatch(1)
        val repeats = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) replayed.countDown()
            }
        }
        // The 12 s song, so that the listened time is surely added before it ends.
        start(item("a"), repeat = Player.REPEAT_MODE_ONE, listener = repeats)
        listen(1_500)

        check(replayed.await(SAFETY_TIMEOUT_S, TimeUnit.SECONDS)) { "No repeat came" }

        // The latch's listener hears the transition before the tracker does: read once the main thread has handled it.
        val first = onMain { outcomes.first() }
        assertThat(first.mediaId to first.outcome).isEqualTo("a" to ListenOutcome.FINISHED)
        assertThat(first.listenedMs).isEqualTo(1_500)
        assertThat(first.startedAt).isEqualTo(wall)
    }

    @Test
    fun theEndOfTheQueueIsListenedToTheEndAndThePlaybackWritesNoOtherOutcome() {
        start(item("a"))
        listen(4_000)

        onMain { player.seekTo(11_000) }
        awaitPlayer("the queue ends") { playbackState == Player.STATE_ENDED }
        assertThat(outcomes()).containsExactly("a" to ListenOutcome.FINISHED)
        assertThat(outcomes.single().listenedMs).isEqualTo(4_000)

        // Play after the end replays the song from its start in the same playback (§11.2); moving on from it writes nothing.
        onMain {
            player.seekTo(0)
            player.play()
        }
        awaitPlayer("it plays again") { isPlaying }
        listen(40_000)
        onMain { player.setMediaItems(listOf(item("b"))) }
        awaitPlayer("b is the queue") { currentMediaItem?.mediaId == "b" }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.FINISHED)
    }

    @Test
    fun aNewQueueOfAnotherSongIsASkipAndOfTheSameSongNone() {
        start(item("a"))
        listen(5_000)

        onMain { player.setMediaItems(listOf(item("a"), item("b")), 0, 0) }
        awaitPlayer("the new queue plays") { mediaItemCount == 2 && isPlaying }
        assertThat(outcomes).isEmpty()

        listen(31_000)
        onMain { player.setMediaItems(listOf(item("c"))) }
        awaitPlayer("c is the queue") { currentMediaItem?.mediaId == "c" }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.LATE_SKIP)
        assertThat(outcomes.single().listenedMs).isEqualTo(31_000)
    }

    @Test
    fun aPauseAndASeekAddNothingToTheListenedTimeOfASkip() {
        start(item("a"), item("b"))
        listen(10_000)
        onMain { player.pause() }
        awaitPlayer("it pauses") { !isPlaying }
        listen(60_000)
        onMain { player.seekTo(9_000) }
        awaitPlayer("it is at 9 s") { currentPosition >= 9_000 && playbackState == Player.STATE_READY }

        // Moved on while paused: a skip by the time listened so far.
        onMain { player.seekToNextMediaItem() }
        awaitPlayer("b is current") { currentMediaItem?.mediaId == "b" }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.EARLY_SKIP)
        assertThat(outcomes.single().listenedMs).isEqualTo(10_000)
    }

    @Test
    fun movingOnAtTheEndOfTheSongWasListenedToTheEnd() {
        start(item("a"), item("b"))
        listen(5_000)
        onMain { player.pause() }
        awaitPlayer("it pauses") { !isPlaying }
        // The end of the 12 s song is its last 2 % (§4.3.1), from about 11.8 s on.
        onMain { player.seekTo(11_900) }
        awaitPlayer("it is at 11.9 s") { currentPosition >= 11_900 }

        onMain { player.seekToNextMediaItem() }
        awaitPlayer("b is current") { currentMediaItem?.mediaId == "b" }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.FINISHED)
        assertThat(outcomes.single().listenedMs).isEqualTo(5_000)
    }

    @Test
    fun aReleaseWritesNoOutcome() {
        start(item("a"))
        listen(40_000)

        onMain { tracker.release() }

        assertThat(outcomes).isEmpty()
    }

    @Test
    fun aRestartFromTheEndOfTheSongEndsItsPlaybackAsListenedToTheEnd() {
        start(item("a"))
        listen(4_000)

        // The check against the player's length that moves a restored song back to its start (docs/playback.md §4.3.1).
        onMain { tracker.restart() }

        assertThat(outcomes()).containsExactly("a" to ListenOutcome.FINISHED)
        assertThat(outcomes.single().listenedMs).isEqualTo(4_000)
    }

    @Test
    fun aSavedPlaybackThatTheRestoreMovesOffTheEndWasListenedToTheEnd() {
        start(item("b"))

        onMain { tracker.finishSaved("a", ListenProgress(listenedMs = 20_000, counted = false, startedAt = 42), durationMs = 180_000) }

        assertThat(outcomes).containsExactly(Outcome("a", ListenOutcome.FINISHED, 42, 20_000, 180_000))
        // The current playback is untouched.
        assertThat(onMain { tracker.progress() }?.listenedMs).isEqualTo(0)
    }

    private companion object {
        /** Far more than any of these waits needs; a wait ends at the event, not at this. */
        const val SAFETY_TIMEOUT_S = 30L
    }
}
