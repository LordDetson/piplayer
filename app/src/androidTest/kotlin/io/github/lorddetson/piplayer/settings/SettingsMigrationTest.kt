package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SharedPreferences of M1 move into the settings files under the same keys and are deleted (docs/library.md §3.2).
 * The test reads into files of its own, so the app's own settings stay as they are.
 */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationTest {

    private val context = TestFiles.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun close() {
        scope.cancel()
        files.forEach { it.delete() }
        listOf("player", "playback_controls", "songs").forEach { context.deleteSharedPreferences(it) }
    }

    private fun store(migrations: List<androidx.datastore.core.DataMigration<androidx.datastore.preferences.core.Preferences>>) =
        PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) {
            File(context.cacheDir, "settings-migration-${UUID.randomUUID()}.preferences_pb").also { files += it }
        }

    private fun sharedPreferencesFile(name: String) = File(context.dataDir, "shared_prefs/$name.xml")

    @Test
    fun theTimersLastChoiceMovesIntoSettings() = runBlocking<Unit> {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit(commit = true) {
            putInt("sleep_timer_hours", 1)
            putInt("sleep_timer_minutes", 15)
            putBoolean("sleep_timer_finish_track", true)
        }

        val preferences = store(SettingsFiles.settingsMigrations(context)).data.first()

        assertThat(preferences[intPreferencesKey("sleep_timer_hours")]).isEqualTo(1)
        assertThat(preferences[intPreferencesKey("sleep_timer_minutes")]).isEqualTo(15)
        assertThat(preferences[booleanPreferencesKey("sleep_timer_finish_track")]).isTrue()
        assertThat(sharedPreferencesFile("player")).doesNotExist()
    }

    @Test
    fun stopAfterCurrentAndThePermissionFlagMoveIntoDeviceState() = runBlocking<Unit> {
        context.getSharedPreferences("playback_controls", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("stop_after_current", true)
        }
        context.getSharedPreferences("songs", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("audio_permission_denied_before", true)
        }

        val preferences = store(SettingsFiles.deviceStateMigrations(context)).data.first()

        assertThat(preferences[booleanPreferencesKey("stop_after_current")]).isTrue()
        assertThat(preferences[booleanPreferencesKey("audio_permission_denied_before")]).isTrue()
        assertThat(sharedPreferencesFile("playback_controls")).doesNotExist()
        assertThat(sharedPreferencesFile("songs")).doesNotExist()
    }
}
