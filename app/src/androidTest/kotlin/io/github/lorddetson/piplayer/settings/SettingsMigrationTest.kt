package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production migrations with temporary settings files. */
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
    fun sharedLayoutsAreMigratedWithoutOverwritingSavedThemeEdits() = runBlocking<Unit> {
        val path = File(context.cacheDir, "settings-migration-${UUID.randomUUID()}.preferences_pb").also { files += it }
        val originalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val original = PreferenceDataStoreFactory.create(scope = originalScope) { path }
        val themeMode = stringPreferencesKey("theme_mode")
        val oldPresentation = stringPreferencesKey("now_playing_layout")
        val oldTabOrder = stringPreferencesKey("library_tab_order")
        val oldHiddenTabs = stringSetPreferencesKey("library_hidden_tabs")
        val savedTheme = CustomTheme("Previously saved", ThemeMode.WARM_LIGHT)
        val editedTabs = LibraryPanelLayout(tabs = LibraryTabLayout.fromStored(listOf("artists"), setOf("songs")))
        try {
            original.edit {
                it[themeMode] = ThemeMode.LIGHT.stored
                it[oldPresentation] = NowPlayingLayout.MINIMAL.stored
                it[oldTabOrder] = "folders,songs"
                it[oldHiddenTabs] = setOf("albums")
                CustomThemesSettings.storeTheme(it, savedTheme, select = false)
                LibraryTabsSettings.storePanel(it, savedTheme.id, editedTabs)
                miniPlayerPanels.storePanel(it, ThemeMode.LIGHT.stored, MiniPlayerPanel(progressAtBottom = true))
            }
        } finally {
            originalScope.coroutineContext.job.cancelAndJoin()
        }

        val file = PreferenceDataStoreFactory.create(migrations = SettingsFiles.settingsMigrations(context), scope = scope) { path }
        val settings = AppearanceSettings(file)
        assertThat(file.data.first()[oldPresentation]).isNull()
        assertThat(file.data.first()[oldTabOrder]).isNull()
        assertThat(file.data.first()[oldHiddenTabs]).isNull()
        val light = settings.exportThemePackage { it.stored }
        assertThat(light.layout).isEqualTo(NowPlayingLayout.MINIMAL)
        assertThat(light.panel?.tabs?.visible).containsSubsequence("folders", "songs")
        assertThat(light.panel?.tabs?.hidden).containsExactly("albums")
        assertThat(light.miniPlayer).isEqualTo(MiniPlayerPanel(progressAtBottom = true))

        settings.setTheme(ThemeMode.GRAPHITE)
        val graphite = settings.exportThemePackage { it.stored }
        assertThat(graphite.layout).isEqualTo(NowPlayingLayout.EXPANDED)
        assertThat(graphite.panel?.tabs?.hidden).isEmpty()
        CustomThemesSettings(file).select(savedTheme.id)
        assertThat(settings.exportThemePackage { it.stored }.panel).isEqualTo(editedTabs)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.MINIMAL)
        settings.setTheme(ThemeMode.LIGHT)
        assertThat(settings.exportThemePackage { it.stored }).isEqualTo(light)
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
