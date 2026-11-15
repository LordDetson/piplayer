package io.github.lorddetson.piplayer.playback.snippet

import android.net.Uri
import android.os.SystemClock
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import io.github.lorddetson.piplayer.playback.PlaybackService
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.holdsThroughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.timerArgs
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.SleepTimer
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * The snippet (TI §6) through the playback service, whose player it holds for the snippet: the service's sleep timer
 * treats the hold as playback (docs/playback.md §6.3), with a 1 s fade. The tests of how the hold ends send the hold's
 * command themselves, as [SnippetPlayer] does, so that no snippet's own focus handling ends it first.
 */
@RunWith(AndroidJUnit4::class)
class SnippetServiceTest {

    @get:Rule
    val fade = object : ExternalResource() {
        override fun before() {
            SleepTimer.fadeMsOverride = FADE_MS
        }

        override fun after() {
            SleepTimer.fadeMsOverride = null
        }
    }

    private val context = TestFiles.targetContext
    private val snippetFile: Uri = Uri.fromFile(TestFiles.copy(SNIPPET_FIXTURE))
    private lateinit var controller: MediaController
    private lateinit var snippet: SnippetPlayer

    @Before
    fun connect() {
        controller = connectController()
        snippet = onMain { SnippetPlayer.create(context, controller) }
    }

    @After
    fun disconnect() {
        onMain { snippet.stop() }
        controller.stopAndRelease()
    }

    private fun timer(durationMs: Long, finishTrack: Boolean = false): Long {
        val started = SystemClock.elapsedRealtime()
        val result = onMain {
            controller.sendCustomCommand(PlaybackSessionCallback.SLEEP_TIMER_COMMAND, timerArgs(durationMs, finishTrack))
        }.get(5, TimeUnit.SECONDS)
        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        return started
    }

    private fun extras(): PlaybackExtras = PlaybackExtras.from(onMain { controller.sessionExtras })

    private fun hold(held: Boolean, resume: Boolean = false) {
        val result = onMain {
            controller.sendCustomCommand(
                PlaybackSessionCallback.SNIPPET_HOLD_COMMAND,
                PlaybackSessionCallback.snippetHoldArgs(held, resume),
            )
        }.get(5, TimeUnit.SECONDS)
        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
    }

    /** Swipes PiPlayer away from recents during a hold; the hold keeps the service. */
    private fun swipeDuringHold(): Int {
        val stops = PlaybackService.swipeStopsForTests
        onMain { checkNotNull(PlaybackService.removeTaskForTests) { "The playback service runs" }() }
        throughout("the service stays after the swipe", 1.seconds) {
            PlaybackService.isRunningForTests && PlaybackService.swipeStopsForTests == stops
        }
        return stops
    }

    private fun playSnippet() {
        onMain { snippet.play(snippetFile, SNIPPET_FIXTURE_MS) }
        eventually("the snippet plays") { onMain { snippet.state.value is SnippetState.Playing } }
        controller.waitUntil("the main player pauses for the snippet") { !playWhenReady }
    }

    @Test
    fun aTimerFinishingTheTrackWhenTheSnippetStartsStillFinishesItAfterTheResume() {
        controller.play(LONG_FIXTURE)
        val started = timer(300, finishTrack = true)
        // Its time comes while the music plays: the song that plays is now being finished.
        SystemClock.sleep((started + 1_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        val finishing = extras()
        assertThat(finishing.sleepDeadlineMs).describedAs("the timer finishing the track").isNotNull().isLessThan(SystemClock.elapsedRealtime())
        assertThat(finishing.finishTrack).isTrue()
        assertThat(onMain { controller.playWhenReady }).describedAs("the song that is being finished plays").isTrue()

        playSnippet()
        throughout("the timer goes on while the snippet holds the main player", 1.seconds) { extras().sleepDeadlineMs != null }
        onMain { snippet.stop() }

        controller.waitUntil("the main player resumes") { playWhenReady }
        throughout("the timer still finishes the track", 1.seconds) {
            val extras = extras()
            extras.sleepDeadlineMs != null && extras.finishTrack
        }
    }

    @Test
    fun aCountdownThatEndsDuringTheSnippetLeavesTheMainPlayerPausedAfterIt() {
        controller.play(LONG_FIXTURE)
        timer(2_000)
        playSnippet()

        eventually("the timer's time comes during the snippet", timeout = 6.seconds) { extras().sleepDeadlineMs == null }
        assertThat(onMain { snippet.state.value }).describedAs("the snippet").isInstanceOf(SnippetState.Playing::class.java)
        onMain { snippet.stop() }

        assertThat(onMain { snippet.state.value }).isEqualTo(SnippetState.Ended(SnippetEnd.CLOSED))
        controller.holdsThroughout("the main player stays paused", 1_500.milliseconds) { !playWhenReady }
        controller.waitUntil("the volume is back") { volume == 1f }
    }

    @Test
    fun aSnippetWithoutATimerResumesTheMainPlayerThroughTheHold() {
        controller.play(LONG_FIXTURE)
        playSnippet()
        assertThat(extras().sleepDeadlineMs).isNull()

        onMain { snippet.stop() }

        controller.waitUntil("the main player resumes") { playWhenReady && isPlaying }
    }

    @Test
    fun anotherControllerPlayingTheMainPlayerEndsTheHold() {
        controller.play(LONG_FIXTURE)
        hold(held = true)
        controller.waitUntil("the main player pauses for the hold") { !playWhenReady }

        val other = connectController()
        try {
            onMain { other.play() }
            controller.waitUntil("the main player plays again") { playWhenReady && isPlaying }

            // A paused player with no hold turns off a timer that is finishing the track (docs/playback.md §6.3).
            val started = timer(300, finishTrack = true)
            SystemClock.sleep((started + 1_000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            assertThat(extras().finishTrack).describedAs("the timer finishing the track").isTrue()
            assertThat(extras().sleepDeadlineMs).describedAs("the timer finishing the track").isNotNull()
            onMain { other.pause() }
            eventually("the user's pause turns the timer off") { extras().sleepDeadlineMs == null }

            hold(held = false, resume = true)
            controller.holdsThroughout("the ended hold resumes nothing", 1_500.milliseconds) { !playWhenReady }
        } finally {
            onMain { other.release() }
        }
    }

    @Test
    fun aSwipeFromRecentsDuringTheHoldKeepsTheServiceForTheResume() {
        controller.play(LONG_FIXTURE)
        hold(held = true)
        controller.waitUntil("the main player pauses for the hold") { !playWhenReady }

        val stops = swipeDuringHold()
        hold(held = false, resume = true)

        controller.waitUntil("the main player resumes") { playWhenReady && isPlaying }
        throughout("the service stays", 1.seconds) {
            PlaybackService.isRunningForTests && PlaybackService.swipeStopsForTests == stops
        }
    }

    @Test
    fun aHoldThatEndsWithoutAResumeAfterASwipeStopsTheService() {
        controller.play(LONG_FIXTURE)
        hold(held = true)
        controller.waitUntil("the main player pauses for the hold") { !playWhenReady }

        val stops = swipeDuringHold()
        hold(held = false, resume = false)

        eventually("the service stops as the swipe would have") { PlaybackService.swipeStopsForTests == stops + 1 }
        controller.holdsThroughout("the main player stays paused", 1.seconds) { !playWhenReady }
    }

    private companion object {
        const val FADE_MS = 1_000L

        /** 60 s of a quiet tone. */
        const val SNIPPET_FIXTURE = "playback/snippet-60s.mp3"
        const val SNIPPET_FIXTURE_MS = 60_000L
    }
}
