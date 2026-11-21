package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

data class Appearance(
    val theme: ThemeMode,
    val nowPlayingLayout: NowPlayingLayout,
    val customTheme: CustomTheme? = null,
)

internal sealed interface ThemeApplyResult {
    data class Applied(val undo: ThemeApplyUndo) : ThemeApplyResult
    data object InvalidTheme : ThemeApplyResult
    data object WriteFailed : ThemeApplyResult
}

internal data class ThemeApplyUndo(
    val previous: Appearance,
    val applied: Appearance,
    val appliedLayoutRevision: Long,
)

internal enum class ThemeUndoResult { RESTORED, STALE, WRITE_FAILED }

/** The Appearance settings in the `settings` file (docs/library.md §3.6). */
class AppearanceSettings internal constructor(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val appearance: Flow<Appearance> = file.data.map(::appearanceOf).distinctUntilChanged()

    /** Reads the saved appearance before the activity draws its first frame. */
    fun read(): Appearance = runBlocking { appearance.first() }

    fun readTheme(): ThemeMode = read().theme

    suspend fun setTheme(mode: ThemeMode) {
        SettingsFiles.edit(file) {
            it[THEME] = mode.stored
            CustomThemesSettings.selectTheme(it, null)
        }
    }

    suspend fun setNowPlayingLayout(layout: NowPlayingLayout) {
        SettingsFiles.edit(file) { storeLayout(it, layout) }
    }

    internal suspend fun exportThemePackage(defaultName: (ThemeMode) -> String): ThemePackage {
        val preferences = file.data.first()
        val current = appearanceOf(preferences)
        return ThemePackage(
            name = current.customTheme?.name ?: defaultName(current.theme),
            base = current.theme,
            palette = current.customTheme?.palette ?: ThemePalette(),
            layout = current.nowPlayingLayout,
            panel = LibraryTabsSettings.panelOf(preferences).layout,
        )
    }

    internal suspend fun applyCustomTheme(
        theme: CustomTheme,
        layout: NowPlayingLayout? = null,
        panel: LibraryPanelLayout? = null,
    ): ThemeApplyResult {
        if (!CustomThemesSettings.isValid(theme)) return ThemeApplyResult.InvalidTheme
        return update(ThemeApplyResult.WriteFailed) { preferences ->
            val previous = appearanceOf(preferences)
            val importedPanel = panel ?: LibraryTabsSettings.panelOf(preferences).layout
            if (!CustomThemesSettings.storeTheme(preferences, theme, select = true, replaceExisting = false)) {
                return@update ThemeApplyResult.InvalidTheme
            }
            LibraryTabsSettings.storePanel(preferences, theme.id, importedPanel)
            if (layout != null) storeLayout(preferences, layout)
            ThemeApplyResult.Applied(ThemeApplyUndo(previous, appearanceOf(preferences), layoutRevision(preferences)))
        }
    }

    internal suspend fun undoCustomTheme(undo: ThemeApplyUndo): ThemeUndoResult =
        update(ThemeUndoResult.WRITE_FAILED) { preferences ->
            val current = appearanceOf(preferences)
            // Check each setting independently so a newer layout or theme choice survives undo.
            val restoreTheme = current.customTheme == undo.applied.customTheme
            val restoreLayout = undo.previous.nowPlayingLayout != undo.applied.nowPlayingLayout &&
                current.nowPlayingLayout == undo.applied.nowPlayingLayout &&
                layoutRevision(preferences) == undo.appliedLayoutRevision

            if (restoreTheme && !CustomThemesSettings.selectTheme(preferences, undo.previous.customTheme?.id)) {
                CustomThemesSettings.selectTheme(preferences, null)
            }
            if (restoreLayout) storeLayout(preferences, undo.previous.nowPlayingLayout)

            if (restoreTheme || restoreLayout) ThemeUndoResult.RESTORED else ThemeUndoResult.STALE
        }

    private suspend fun <T> update(writeFailure: T, change: (MutablePreferences) -> T): T = try {
        var result = writeFailure
        file.edit { result = change(it) }
        result
    } catch (_: IOException) {
        writeFailure
    }

    internal companion object {
        private val THEME = stringPreferencesKey("theme_mode")
        private val NOW_PLAYING_LAYOUT = stringPreferencesKey("now_playing_layout")
        private val LAYOUT_REVISION = longPreferencesKey("now_playing_layout_revision")

        fun panelThemeId(preferences: Preferences): String = CustomThemesSettings.selectedTheme(preferences)?.id
            ?: ThemeMode.fromStored(preferences[THEME]).stored

        private fun layoutRevision(preferences: Preferences): Long = preferences[LAYOUT_REVISION] ?: 0L

        private fun storeLayout(preferences: MutablePreferences, layout: NowPlayingLayout) {
            preferences[NOW_PLAYING_LAYOUT] = layout.stored
            // A later manual choice may return to the same value without reviving an old undo.
            preferences[LAYOUT_REVISION] = layoutRevision(preferences) + 1L
        }

        private fun appearanceOf(preferences: Preferences): Appearance {
            val custom = CustomThemesSettings.selectedTheme(preferences)
            return Appearance(
                theme = custom?.base ?: ThemeMode.fromStored(preferences[THEME]),
                nowPlayingLayout = NowPlayingLayout.fromStored(preferences[NOW_PLAYING_LAYOUT]),
                customTheme = custom,
            )
        }
    }
}
