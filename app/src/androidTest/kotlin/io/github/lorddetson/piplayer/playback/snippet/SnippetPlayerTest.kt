package io.github.lorddetson.piplayer.playback.snippet

import android.media.AudioManager
import android.media.AudioFocusRequest
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.AudioFocus
import io.github.lorddetson.piplayer.playback.Expectation
import io.github.lorddetson.piplayer.playback.ListenProgress
import io.github.lorddetson.piplayer.playback.PlayRecorder
import io.github.lorddetson.piplayer.playback.PlayTracker
import io.github.lorddetson.piplayer.playback.PlaybackPlayer
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.expect
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.shell
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snippet (TI §6, §11.2) against a main player built as the playback service builds it, with Media3's focus
 * handling, behind a media session; the snippet reaches it only through a MediaController, as the card does. The main
 * player's play counter runs on the test's clock, which stands still unless the test moves it: time moved on while the
 * snippet plays would count for the main player only if it played.
 */
@RunWith(AndroidJUnit4::class)
class SnippetPlayerTest {

    private val context = TestFiles.targetContext
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val recorded = CopyOnWriteArrayList<String>()
    private val recorder = object : PlayRecorder {
        override fun counted(mediaId: String, startedAt: Long, listenedMs: Long) {
            recorded += "counted $mediaId"
        }

        override fun ended(mediaId: String, startedAt: Long, listenedMs: Long) {
            recorded += "ended $mediaId"
        }
    }

    @Volatile
    private var now = 100_000L
    private lateinit var main: ExoPlayer
    private lateinit var tracker: PlayTracker
    private lateinit var session: MediaSession
    private lateinit var controller: MediaController
    private lateinit var snippet: SnippetPlayer
    private var takenFocus: AudioFocusRequest? = null

    private val longFile: Uri = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))

    @Before
    fun startTheMainPlayer() {
        onMain {
            main = PlaybackPlayer.builder(context).build()
            tracker = PlayTracker(
                main,
                recorder,
                clock = { now },
                wallClock = { 1_800_000_000_000L },
                thresholdOf = { 5_000L },
            )
            tracker.start()
            main.setMediaItems(listOf(mainItem("first"), mainItem("second")))
            main.prepare()
            session = MediaSession.Builder(context, main).setId("snippet-test-${sessions.incrementAndGet()}").build()
        }
        controller = onMain { MediaController.Builder(context, session.token).buildAsync() }.get(10, TimeUnit.SECONDS)
        snippet = onMain { SnippetPlayer.create(context, controller) }
    }

    @After
    fun release() {
        takenFocus?.let(audioManager::abandonAudioFocusRequest)
        onMain {
            snippet.stop()
            controller.release()
            session.release()
            tracker.release()
            main.release()
        }
    }

    private fun mainItem(id: String) = MediaItem.Builder().setMediaId(id).setUri(longFile).build()

    private fun startMain() {
        onMain { controller.play() }
        controller.waitUntil("the main player plays") { isPlaying && currentPosition > 300 }
    }

    /** Plays a snippet and waits until it is heard, returning the first position it played from. */
    private fun playSnippet(uri: Uri = longFile, durationMs: Long? = LONG_FIXTURE_MS): Long {
        onMain { snippet.play(uri, durationMs) }
        eventually("the snippet plays") { onMain { snippet.state.value is SnippetState.Playing } }
        val first = onMain { snippet.positionMs }
        eventually("the snippet moves on") { onMain { snippet.positionMs } > first + 200 }
        return first
    }

    private fun awaitEnd(timeout: kotlin.time.Duration = 10.seconds): SnippetEnd {
        eventually("the snippet ends", timeout) { onMain { snippet.state.value is SnippetState.Ended } }
        return onMain { (snippet.state.value as SnippetState.Ended).reason }
    }

    private class MainState(val index: Int, val ids: List<String>, val positionMs: Long, val listen: ListenProgress?)

    private fun mainState() = onMain {
        MainState(
            controller.currentMediaItemIndex,
            (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId },
            main.currentPosition,
            tracker.progress(),
        )
    }

    /** The main player paused for the snippet; its state then, which must not change until it resumes. */
    private fun pausedForTheSnippet(): MainState {
        controller.waitUntil("the main player pauses for the snippet") { !playWhenReady }
        standsStill()
        return mainState()
    }

    /** Waits until the main player has paused and its playback thread has reported where it stopped. */
    private fun standsStill() {
        eventually("the main player stops playing") { onMain { !main.isPlaying } }
        var last = -1L
        eventually("the main player's position settles") {
            val position = onMain { main.currentPosition }
            (position == last).also { last = position; if (!it) Thread.sleep(300) }
        }
    }

    private fun assertUnchanged(before: MainState) {
        val after = mainState()
        assertThat(after.index).describedAs("current item").isEqualTo(before.index)
        assertThat(after.ids).describedAs("queue").isEqualTo(before.ids)
        assertThat(after.positionMs).describedAs("position").isEqualTo(before.positionMs)
        assertThat(after.listen).describedAs("the play counter's playback and listened time").isEqualTo(before.listen)
        assertThat(recorded).describedAs("recorded plays").isEmpty()
    }

    /** The main player resumes where it paused: the first position it plays from is where it stood. */
    private fun assertResumesAt(stoodAtMs: Long, resumed: Expectation<ExoPlayer, Long>) {
        val positionMs = resumed.await()
        assertThat(positionMs).describedAs("position on resuming").isBetween(stoodAtMs, stoodAtMs + 500)
    }

    /** The audio focus stack's entries of this app (`dumpsys audio`), top of the stack last. */
    private fun ownFocusEntries(): List<String> = shell("dumpsys audio")
        .substringAfter("Audio Focus stack entries", "")
        .substringBefore("\n\n")
        .lines()
        .filter { "pack: ${context.packageName} " in it }

    private fun transientRequests() = ownFocusEntries().count { TRANSIENT_GAIN.containsMatchIn(it) }

    @Test
    fun aPlayingMainPlayerPausesForTheWholeSnippetFromOneThirdAndResumesWhereItWas() {
        startMain()
        val started = TimeSource.Monotonic.markNow()

        val first = playSnippet()
        val before = pausedForTheSnippet()
        assertThat(first).describedAs("the snippet's first position").isBetween(19_500L, 21_000L)
        assertThat(transientRequests())
            .describedAs("transient focus requests while the snippet plays: ${ownFocusEntries()}")
            .isEqualTo(1)
        // Time that would count for the main player if it played: three times its threshold.
        now += 15_000

        var last = first
        val resumed = main.expect("the main player resumes", { currentPosition }) { isPlaying }
        while (onMain { snippet.state.value is SnippetState.Playing }) {
            last = maxOf(last, onMain { snippet.positionMs })
            Thread.sleep(50)
        }
        val playedFor = started.elapsedNow()

        assertThat(awaitEnd()).isEqualTo(SnippetEnd.FINISHED)
        assertThat(last).describedAs("the snippet's last position").isBetween(34_000L, 35_300L)
        assertThat(playedFor).describedAs("how long the snippet played").isGreaterThan(14.seconds)
        assertResumesAt(before.positionMs, resumed)
        eventually("the snippet's focus request is gone") { transientRequests() == 0 }
        // The same playback goes on and counts again: a second of it adds a second.
        now += 1_000
        onMain { controller.pause() }
        controller.waitUntil("the main player pauses") { !playWhenReady }
        standsStill()
        val listened = onMain { tracker.progress() }!!
        assertThat(listened.startedAt).describedAs("same playback").isEqualTo(before.listen!!.startedAt)
        assertThat(listened.listenedMs).describedAs("listened time").isEqualTo(before.listen.listenedMs + 1_000)
        assertThat(recorded).describedAs("recorded plays").isEmpty()
        onMain {
            assertThat(controller.currentMediaItemIndex).isEqualTo(before.index)
            val ids = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
            assertThat(ids).isEqualTo(before.ids)
        }
    }

    @Test
    fun closingTheCardEndsTheSnippetAndResumesTheMainPlayer() {
        startMain()
        playSnippet()
        val before = pausedForTheSnippet()
        now += 15_000

        val resumed = main.expect("the main player resumes", { currentPosition }) { isPlaying }
        assertUnchanged(before)
        onMain { snippet.stop() }

        assertThat(awaitEnd()).isEqualTo(SnippetEnd.CLOSED)
        assertResumesAt(before.positionMs, resumed)
        eventually("the snippet's focus request is gone") { transientRequests() == 0 }
    }

    @Test
    fun aMainPlayerPausedBeforeTheSnippetStaysPaused() {
        startMain()
        onMain { controller.pause() }
        controller.waitUntil("the main player pauses") { !playWhenReady }
        standsStill()
        val before = mainState()

        playSnippet()
        now += 15_000
        onMain { snippet.stop() }

        assertThat(awaitEnd()).isEqualTo(SnippetEnd.CLOSED)
        throughout("the main player stays paused", 1.seconds) {
            onMain { !controller.playWhenReady && !main.isPlaying }
        }
        assertUnchanged(before)
    }

    @Test
    fun aShortFilePlaysFromItsStartAndTheSnippetEndsWithIt() {
        startMain()
        val resumed = main.expect("the main player resumes after the snippet", { currentPosition }) {
            isPlaying && snippet.state.value is SnippetState.Ended
        }

        val first = playSnippet(Uri.fromFile(TestFiles.copy(SHORT_FIXTURE)), durationMs = 2_000)
        val before = pausedForTheSnippet()

        assertThat(first).describedAs("the snippet's first position").isLessThan(500)
        assertThat(awaitEnd()).isEqualTo(SnippetEnd.FINISHED)
        assertResumesAt(before.positionMs, resumed)
    }

    @Test
    fun anotherPlayerTakingFocusStopsTheSnippetWithoutResumingTheMainPlayer() {
        startMain()
        playSnippet()
        val before = pausedForTheSnippet()

        takenFocus = AudioFocus.take(audioManager, AudioManager.AUDIOFOCUS_GAIN)

        assertThat(awaitEnd()).isEqualTo(SnippetEnd.FOCUS_LOST)
        takenFocus?.let(audioManager::abandonAudioFocusRequest)
        takenFocus = null
        throughout("the main player stays paused", 1_500.milliseconds) {
            onMain { !controller.playWhenReady && !main.isPlaying }
        }
        assertUnchanged(before)
        eventually("the snippet's focus request is gone") { transientRequests() == 0 }
    }

    @Test
    fun aCallStopsTheSnippetAndTheMainPlayerStaysPausedAfterTheCall() {
        startMain()
        playSnippet()
        val before = pausedForTheSnippet()

        takenFocus = AudioFocus.take(audioManager, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        assertThat(awaitEnd()).isEqualTo(SnippetEnd.FOCUS_LOST)
        throughout("the main player stays paused during the call", 1.seconds) { onMain { !controller.playWhenReady } }

        // The call ends: the focus goes back down the stack, to the main player, which must still not play.
        takenFocus?.let(audioManager::abandonAudioFocusRequest)
        takenFocus = null
        throughout("the main player stays paused after the call", 1_500.milliseconds) {
            onMain { !controller.playWhenReady && !main.isPlaying }
        }
        assertUnchanged(before)
    }

    private companion object {
        /** 60 s of a quiet tone: the snippet plays it from 20 s to 35 s. */
        const val LONG_FIXTURE = "playback/snippet-60s.mp3"
        const val LONG_FIXTURE_MS = 60_000L

        /** A request for `AUDIOFOCUS_GAIN_TRANSIENT` exactly, not one of its `_MAY_DUCK` or `_EXCLUSIVE` kinds. */
        val TRANSIENT_GAIN = Regex("gain: GAIN_TRANSIENT(\\s|$)")

        /** Every test's session needs its own ID while an earlier one may not have gone yet. */
        val sessions = AtomicInteger()
    }
}
