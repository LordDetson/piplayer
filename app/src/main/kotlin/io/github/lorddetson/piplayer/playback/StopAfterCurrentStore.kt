package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps "stop after current track" on until it has stopped playback (docs/playback.md §6.4), across a closed app and a
 * killed process. Like the saved queue it stays out of backups: it lives in the `device_state` file (docs/library.md
 * §3.2). The one read, when the service starts, blocks; writes come from player events on the main thread, so they
 * run in the background, one after another.
 */
class StopAfterCurrentStore(context: Context) {

    private val file = SettingsFiles.deviceState(context)

    fun read(): Boolean = runBlocking {
        // After the writes started so far.
        lastWrite?.join()
        file.data.first()[STOP_AFTER_CURRENT] ?: false
    }

    fun save(enabled: Boolean) = synchronized(Companion) {
        val previous = lastWrite
        lastWrite = scope.launch {
            previous?.join()
            SettingsFiles.edit(file) { it[STOP_AFTER_CURRENT] = enabled }
        }
    }

    private companion object {
        // Writes outlive the service that asked for them.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var lastWrite: Job? = null
        val STOP_AFTER_CURRENT = booleanPreferencesKey("stop_after_current")
    }
}
