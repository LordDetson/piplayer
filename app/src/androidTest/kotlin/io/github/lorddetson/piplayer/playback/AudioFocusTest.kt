package io.github.lorddetson.piplayer.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.holdsWakeLock
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audio focus (docs/playback.md §1.2). The test takes focus with its own request, as another player, a call (a
 * transient loss) or a prompt would. It runs in PiPlayer's process, so the platform passes every focus change to the
 * player instead of lowering the volume itself; here a prompt that allows ducking can only show that playback goes on
 * (DuckingTest checks the lower volume on a player of its own).
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusTest {

    private val audioManager = TestFiles.targetContext.getSystemService(AudioManager::class.java)
    private lateinit var controller: MediaController
    private val pauseReasons = CopyOnWriteArrayList<Int>()
    private var request: AudioFocusRequest? = null

    @Before
    fun startPlaying() {
        controller = connectController()
        onMain {
            controller.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pauseReasons += reason
                }
            })
        }
        controller.play(LONG_FIXTURE)
    }

    @After
    fun cleanUp() {
        giveFocusBack()
        controller.stopAndRelease()
    }

    @Test
    fun anotherPlayerTakingFocusForGoodPausesWithoutResuming() {
        takeFocus(AudioManager.AUDIOFOCUS_GAIN)

        controller.waitUntil("playback pauses") { !playWhenReady }
        assertThat(pauseReasons).containsExactly(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)

        giveFocusBack()
        throughout("playback stays paused", 1.seconds) { onMain { !controller.playWhenReady } }
    }

    @Test
    fun aShortInterruptionHoldsPlaybackAndItResumesAfterwards() {
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

        waitUntilHeld()
        // Held playback is still playback: the device must not sleep before it resumes.
        assertThat(holdsWakeLock()).describedAs("wake lock while held").isTrue()

        giveFocusBack()
        controller.waitUntil("playback resumes") { isPlaying }
        assertThat(pauseReasons).isEmpty()
    }

    @Test
    fun aPausePressedDuringAnInterruptionStays() {
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        waitUntilHeld()

        onMain { controller.pause() }
        giveFocusBack()

        throughout("playback stays paused", 1.seconds) { onMain { !controller.isPlaying && !controller.playWhenReady } }
    }

    @Test
    fun swipingPiPlayerAwayDuringAnInterruptionKeepsPlaybackForAfterwards() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            waitUntilHeld()

            // What swiping the app away from recents does: the task goes, and the playback service hears of it.
            scenario.onActivity { it.finishAndRemoveTask() }
            eventually("the task is gone") { scenario.state == Lifecycle.State.DESTROYED }
            throughout("playback stays held", 1.seconds) { onMain { controller.playWhenReady } }

            giveFocusBack()
            controller.waitUntil("playback resumes") { isPlaying }
        }
    }

    @Test
    fun aShortPromptThatAllowsDuckingKeepsPlaybackGoing() {
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

        throughout("playback goes on", 1_500.milliseconds) { onMain { controller.isPlaying } }
    }

    private fun waitUntilHeld() = controller.waitUntil("playback is held for the interruption") {
        !isPlaying && playWhenReady &&
            playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
    }

    private fun takeFocus(gain: Int) {
        request = AudioFocus.take(audioManager, gain)
    }

    private fun giveFocusBack() {
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
    }
}

/** Focus requests of the tests, standing in for other players, calls and prompts. */
object AudioFocus {

    fun take(audioManager: AudioManager, gain: Int): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .build()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper()))
            .build()
        assertThat(audioManager.requestAudioFocus(request)).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return request
    }
}
