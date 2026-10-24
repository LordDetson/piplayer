package io.github.lorddetson.piplayer.ui.player

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** The device's effect panel for PiPlayer's audio session (docs/playback.md §6.6). */
object EqualizerPanel {

    /** The panel's intent for [sessionId], to be started for a result: a panel closes at once without a caller. */
    fun intent(sessionId: Int, packageName: String): Intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

    /** Whether the device has a panel; PiPlayer sees it through the action under `<queries>` in its manifest. */
    fun isAvailable(context: Context): Boolean =
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).resolveActivity(context.packageManager) != null
}
