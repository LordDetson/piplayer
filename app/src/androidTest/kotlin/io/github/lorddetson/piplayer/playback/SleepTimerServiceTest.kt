package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.holdsThroughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.timerArgs
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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

/** The sleep timer through the playback service, with a 1 s fade (docs/playback.md §6.3, §6.5). */
@RunWith(AndroidJUnit4::class)
class SleepTimerServiceTest {

    @get:Rule
    val fade = object : ExternalResource() {
        override fun before() {
            SleepTimer.fadeMsOverride = FADE_MS
        }

        override fun after() {
            SleepTimer.fadeMsOverride = null
        }
    }

    private lateinit var controller: MediaController

    /** (elapsed realtime, volume) as the controller sees them. */
    private val volumes = CopyOnWriteArrayList<Pair<Long, Float>>()
    private val pauses = CopyOnWriteArrayList<Long>()

    @Before
    fun connect() {
        controller = connectController()
        onMain {
            controller.addListener(object : Player.Listener {
                override fun onVolumeChanged(volume: Float) {
                    volumes += SystemClock.elapsedRealtime() to volume
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pauses += SystemClock.elapsedRealtime()
                }
            })
        }
    }

    @After
    fun disconnect() {
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

    @Test
    fun theVolumeFallsOverTheFadeAndPlaybackPausesAtTheTimeWithTheVolumeBack() {
        controller.play(LONG_FIXTURE)
        val started = timer(3_000)
        assertThat(extras().sleepDeadlineMs).isBetween(started + 2_900, started + 3_100)

        controller.waitUntil("playback pauses", timeout = 8.seconds) { !playWhenReady }

        val pausedAt = pauses.first() - started
        assertThat(pausedAt).isBetween(2_900L, 3_700L)
        val beforeFade = volumes.filter { it.first - started < 3_000 - FADE_MS - 100 }
        assertThat(beforeFade).describedAs("no change before the fade").isEmpty()
        // The steps down to the pause; the volume comes back to 1 in the same turn as the pause.
        val fading = volumes.map { it.second }.takeWhile { it < 1f }
        assertThat(fading).describedAs("the fade").isNotEmpty().isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(fading.min()).isLessThan(0.1f)
        controller.waitUntil("the volume is back") { volume == 1f }
        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(0)
        assertThat(onMain { controller.currentPosition }).isBetween(2_500L, 4_500L)
        assertThat(onMain { controller.mediaItemCount }).isEqualTo(1)
        eventually("the timer is off") { extras().sleepDeadlineMs == null }
    }

    @Test
    fun aPauseDuringTheFadeEndsTheTimerAndRestoresTheVolume() {
        controller.play(LONG_FIXTURE)
        timer(3_000)
        controller.waitUntil("the fade has begun", timeout = 5.seconds) { volume < 0.9f }

        onMain { controller.pause() }

        controller.waitUntil("the volume is back") { volume == 1f }
        eventually("the timer is off") { extras().sleepDeadlineMs == null }
        onMain { controller.play() }
        controller.holdsThroughout("playback goes on at full volume", 2_500.milliseconds) { playWhenReady && volume == 1f }
    }

    @Test
    fun aTimerThatIsUpWhileNothingPlaysJustEnds() {
        controller.play(LONG_FIXTURE)
        onMain { controller.pause() }
        timer(1_500)

        eventually("the timer is off", timeout = 4.seconds) { extras().sleepDeadlineMs == null }
        assertThat(volumes).isEmpty()
        onMain { controller.play() }
        controller.holdsThroughout("playback goes on at full volume", 2.seconds) { playWhenReady && volume == 1f }
    }

    @Test
    fun finishTrackModePausesAtTheEndOfTheSongWithoutAFade() {
        val items = listOf(fileItem(SHORT_FIXTURE), fileItem(LONG_FIXTURE))
        onMain {
            controller.setMediaItems(items)
            controller.prepare()
            controller.play()
        }
        controller.waitUntil("the first song plays") { isPlaying }
        timer(500, finishTrack = true)
        eventually("the timer finishes the track") { extras().finishTrack }

        controller.waitUntil("playback pauses", timeout = 5.seconds) { !playWhenReady }

        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(0)
        assertThat(onMain { controller.currentPosition }).isGreaterThanOrEqualTo(1_800L)
        assertThat(volumes).isEmpty()
        eventually("the timer is off") { extras().sleepDeadlineMs == null }
    }

    @Test
    fun aNewTimerReplacesTheRunningOneAndZeroCancels() {
        controller.play(LONG_FIXTURE)
        timer(60_000)
        val first = extras().sleepDeadlineMs!!

        val started = timer(120_000)
        assertThat(extras().sleepDeadlineMs).isNotEqualTo(first).isBetween(started + 119_000, started + 121_000)

        timer(0)
        eventually("the timer is off") { extras().sleepDeadlineMs == null }
    }

    @Test
    fun theStateReachesPiPlayersControllersButNotThePlatformSession() {
        controller.play(LONG_FIXTURE)
        timer(60_000)
        eventually("the controller knows the deadline") { extras().sleepDeadlineMs != null }

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
            val platformExtras = onMain { listOfNotNull(platform.extras, platform.playbackState?.extras) }
            assertThat(platformExtras.flatMap(Bundle::keySet)).noneMatch { it.startsWith("io.github.lorddetson.piplayer") }
        } finally {
            onMain { browser.disconnect() }
        }
    }

    private companion object {
        const val FADE_MS = 1_000L
    }
}
