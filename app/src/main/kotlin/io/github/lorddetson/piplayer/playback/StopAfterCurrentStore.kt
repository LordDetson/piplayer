package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.core.content.edit

/**
 * Keeps "stop after current track" on until it has stopped playback (docs/playback.md §6.4), across a closed app and a
 * killed process. Like the saved queue it stays out of backups, which `res/xml` names by its file.
 */
class StopAfterCurrentStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): Boolean = preferences.getBoolean(STOP_AFTER_CURRENT, false)

    fun save(enabled: Boolean) = preferences.edit { putBoolean(STOP_AFTER_CURRENT, enabled) }

    private companion object {
        const val PREFERENCES = "playback_controls"
        const val STOP_AFTER_CURRENT = "stop_after_current"
    }
}
