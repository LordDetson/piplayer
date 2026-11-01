package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Keeps "stop after current track" on until it has stopped playback (docs/playback.md §6.4), across a closed app and a
 * killed process. Like the saved queue it stays out of backups: it lives in the `device_state` file (docs/library.md
 * §3.2). Reads and writes are small and rare, so they block.
 */
class StopAfterCurrentStore(context: Context) {

    private val file = SettingsFiles.deviceState(context)

    fun read(): Boolean = runBlocking { file.data.first()[STOP_AFTER_CURRENT] ?: false }

    fun save(enabled: Boolean) {
        runBlocking { file.edit { it[STOP_AFTER_CURRENT] = enabled } }
    }

    private companion object {
        val STOP_AFTER_CURRENT = booleanPreferencesKey("stop_after_current")
    }
}
