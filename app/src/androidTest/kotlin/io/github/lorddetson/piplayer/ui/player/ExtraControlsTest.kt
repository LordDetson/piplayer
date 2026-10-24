package io.github.lorddetson.piplayer.ui.player

import android.os.SystemClock
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Now Playing's second row: the sleep timer, "stop after current track" and the Equalizer (docs/playback.md §6.1). */
@RunWith(AndroidJUnit4::class)
class ExtraControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val stopAfter = mutableListOf<Boolean>()
    private var equalizerOpened = false

    private fun show(extras: PlaybackExtras = PlaybackExtras.NONE, equalizer: Boolean = false) {
        val player = compose.runOnUiThread { TestPlayer(listOf(TestSong("Halo", durationMs = 225_000))) }
        val controls = ExtraControls(
            extras = extras,
            setStopAfterCurrent = { stopAfter += it },
            openEqualizer = if (equalizer) ({ equalizerOpened = true }) else null,
        )
        compose.setContent { PiPlayerTheme { NowPlaying(player, null, onClose = {}, controls = controls) } }
    }

    @Test
    fun theTimerButtonOpensTheSheet() {
        show()

        compose.onNodeWithContentDescription(text(R.string.player_sleep_timer)).performClick()

        compose.onNodeWithText(text(R.string.timer_start)).assertIsDisplayed()
    }

    @Test
    fun aRunningTimerShowsTheTimeLeftOnItsButton() {
        show(PlaybackExtras(sleepDeadlineMs = SystemClock.elapsedRealtime() + 42 * 60_000L))

        compose.onNodeWithText("0:42").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.player_sleep_timer))
            .assert(hasStateDescription(text(R.string.player_sleep_timer_left).format(PlayerTime.spoken(42 * 60_000L, Locale.getDefault()))))
    }

    @Test
    fun stopAfterCurrentIsAToggle() {
        show()
        val toggle = compose.onNodeWithContentDescription(text(R.string.player_stop_after_current))

        toggle.assertIsOff().performClick()

        assertThat(stopAfter).containsExactly(true)
    }

    @Test
    fun stopAfterCurrentShowsTheServicesState() {
        show(PlaybackExtras(stopAfterCurrent = true))

        compose.onNodeWithContentDescription(text(R.string.player_stop_after_current)).assertIsOn().performClick()

        assertThat(stopAfter).containsExactly(false)
    }

    @Test
    fun theEqualizerShowsOnlyWithAPanel() {
        show(equalizer = false)

        compose.onNodeWithContentDescription(text(R.string.player_equalizer)).assertDoesNotExist()
    }

    @Test
    fun theEqualizerOpensThePanel() {
        show(equalizer = true)

        compose.onNodeWithContentDescription(text(R.string.player_equalizer)).performClick()

        assertThat(equalizerOpened).isTrue()
    }
}
