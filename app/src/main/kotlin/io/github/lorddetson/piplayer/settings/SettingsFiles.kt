package io.github.lorddetson.piplayer.settings

import android.content.Context
import android.util.Log
import java.io.IOException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.lorddetson.piplayer.ProcessRole

/**
 * The two settings files (docs/library.md §3.2): `settings` goes into backups, `device_state` does not; their names are
 * also in the backup rules. A file that cannot be read starts empty rather than stopping the app.
 */
object SettingsFiles {

    /**
     * Changes [file]; a write that fails, as on a full disk, is logged and changes nothing, as a failed write of the
     * SharedPreferences of M1 did. Returns the stored settings, or null when the write failed.
     */
    suspend fun edit(file: DataStore<Preferences>, change: (MutablePreferences) -> Unit): Preferences? = try {
        file.edit(change)
    } catch (e: IOException) {
        Log.w(TAG, "A settings write failed", e)
        null
    }

    private const val TAG = "SettingsFiles"

    const val SETTINGS = "settings"
    const val DEVICE_STATE = "device_state"

    /** `player` (the sleep timer's last choice) moves into `settings`. */
    fun settingsMigrations(context: Context): List<DataMigration<Preferences>> =
        listOf(SharedPreferencesMigration(context, "player"))

    /** `playback_controls` (stop after current track) and `songs` (the permission flag) move into `device_state`. */
    fun deviceStateMigrations(context: Context): List<DataMigration<Preferences>> =
        listOf(SharedPreferencesMigration(context, "playback_controls"), SharedPreferencesMigration(context, "songs"))

    /** The `settings` file of this process; DataStore allows one process per file. */
    fun settings(context: Context): DataStore<Preferences> {
        ProcessRole.current(context).requireMain("The settings file")
        return context.applicationContext.settingsDataStore
    }

    /** The `device_state` file of this process. */
    fun deviceState(context: Context): DataStore<Preferences> {
        ProcessRole.current(context).requireMain("The device state file")
        return context.applicationContext.deviceStateDataStore
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsFiles.SETTINGS,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = SettingsFiles::settingsMigrations,
)

private val Context.deviceStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SettingsFiles.DEVICE_STATE,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = SettingsFiles::deviceStateMigrations,
)
