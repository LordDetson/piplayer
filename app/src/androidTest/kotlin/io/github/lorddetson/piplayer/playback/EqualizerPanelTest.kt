package io.github.lorddetson.piplayer.playback

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.EqualizerPanel
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The device's effect panel for PiPlayer's audio session (docs/playback.md §6.6). */
@RunWith(AndroidJUnit4::class)
class EqualizerPanelTest {

    private val context = TestFiles.targetContext

    @Test
    fun thePanelIntentNamesPiPlayersSession() {
        val intent = EqualizerPanel.intent(sessionId = 42, packageName = context.packageName)

        assertThat(intent.action).isEqualTo(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        assertThat(intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)).isEqualTo(42)
        assertThat(intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)).isEqualTo(context.packageName)
        assertThat(intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1)).isEqualTo(AudioEffect.CONTENT_TYPE_MUSIC)
    }

    /** PiPlayer sees every panel the device has, which needs the action under `<queries>` in the manifest. */
    @Test
    fun piPlayerSeesTheDevicesPanels() {
        val shell = PlaybackTesting.shell(
            "cmd package query-activities --brief --components -a ${AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL}",
        )
        val devicePanels = shell.lines().map(String::trim).filter { "/" in it }

        assertThat(EqualizerPanel.isAvailable(context)).isEqualTo(devicePanels.isNotEmpty())
        val seen = context.packageManager.queryIntentActivities(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL), 0)
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        val expected = devicePanels.map { component ->
            val (pkg, cls) = component.split("/", limit = 2)
            "$pkg/${if (cls.startsWith(".")) pkg + cls else cls}"
        }
        assertThat(seen).containsAll(expected)
    }
}
