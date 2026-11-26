package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    val nowPlayingMoodBackground: Boolean = true,
) {
    internal val themeId: String get() = customTheme?.id ?: theme.stored
}

internal sealed interface ThemeApplyResult {
    data class Applied(val undo: ThemeApplyUndo) : ThemeApplyResult
    data object InvalidTheme : ThemeApplyResult
    data object WriteFailed : ThemeApplyResult
}

internal data class ThemeApplyUndo(
    val previousThemeId: String?,
    val appliedThemeId: String,
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

    suspend fun setNowPlayingLayout(layout: NowPlayingLayout, themeId: String? = null) {
        SettingsFiles.edit(file) { preferences ->
            val activeThemeId = panelThemeId(preferences)
            if (themeId == null || themeId == activeThemeId) {
                preferences[themeLayoutKey(activeThemeId)] = layout.stored
            }
        }
    }

    suspend fun setNowPlayingMoodBackground(enabled: Boolean) {
        SettingsFiles.edit(file) { it[MOOD_BACKGROUND] = enabled }
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
            player = nowPlayingPanels.stateOf(preferences).panel,
            miniPlayer = miniPlayerPanels.stateOf(preferences).panel,
            navigation = bottomNavigationLayouts.stateOf(preferences).panel,
            canvases = UiCanvasSettings.layoutsOf(preferences).takeUnless(UiThemeCanvases::isEmpty),
        )
    }

    internal suspend fun applyCustomTheme(
        theme: CustomTheme,
        layout: NowPlayingLayout? = null,
        panel: LibraryPanelLayout? = null,
        player: NowPlayingPanel? = null,
        miniPlayer: MiniPlayerPanel? = null,
        navigation: BottomNavigationLayout? = null,
        canvases: UiThemeCanvases? = null,
    ): ThemeApplyResult {
        if (!CustomThemesSettings.isValid(theme) || canvases?.isValid == false) return ThemeApplyResult.InvalidTheme
        return update(ThemeApplyResult.WriteFailed) { preferences ->
            val previous = appearanceOf(preferences)
            val importedPanel = panel ?: LibraryTabsSettings.panelOf(preferences).layout
            val importedPlayer = player ?: nowPlayingPanels.stateOf(preferences).panel
            val importedMiniPlayer = miniPlayer ?: miniPlayerPanels.stateOf(preferences).panel
            val importedNavigation = navigation ?: bottomNavigationLayouts.stateOf(preferences).panel
            val importedCanvases = canvases ?: UiCanvasSettings.layoutsOf(preferences).copy(gestures = UiGestureProfile())
            if (ThemePackageCodec.hidesLibraryGestureReplacement(importedCanvases, importedPanel)) {
                return@update ThemeApplyResult.InvalidTheme
            }
            if (!CustomThemesSettings.storeTheme(preferences, theme, select = true, replaceExisting = false)) {
                return@update ThemeApplyResult.InvalidTheme
            }
            LibraryTabsSettings.storePanel(preferences, theme.id, importedPanel)
            nowPlayingPanels.storePanel(preferences, theme.id, importedPlayer)
            miniPlayerPanels.storePanel(preferences, theme.id, importedMiniPlayer)
            bottomNavigationLayouts.storePanel(preferences, theme.id, importedNavigation)
            UiCanvasSettings.store(preferences, theme.id, importedCanvases)
            preferences[themeLayoutKey(theme.id)] = (layout ?: previous.nowPlayingLayout).stored
            ThemeApplyResult.Applied(ThemeApplyUndo(previous.customTheme?.id, theme.id))
        }
    }

    internal suspend fun undoCustomTheme(undo: ThemeApplyUndo): ThemeUndoResult =
        update(ThemeUndoResult.WRITE_FAILED) { preferences ->
            if (panelThemeId(preferences) != undo.appliedThemeId) return@update ThemeUndoResult.STALE
            if (!CustomThemesSettings.selectTheme(preferences, undo.previousThemeId)) {
                CustomThemesSettings.selectTheme(preferences, null)
            }
            ThemeUndoResult.RESTORED
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
        private val MOOD_BACKGROUND = booleanPreferencesKey("now_playing_mood_background")

        fun builtInTheme(preferences: Preferences): ThemeMode = ThemeMode.fromStored(preferences[THEME])

        fun panelThemeId(preferences: Preferences): String = CustomThemesSettings.selectedTheme(preferences)?.id
            ?: builtInTheme(preferences).stored

        fun themeLayoutKey(themeId: String) = stringPreferencesKey("now_playing_presentation_$themeId")

        private fun appearanceOf(preferences: Preferences): Appearance {
            val custom = CustomThemesSettings.selectedTheme(preferences)
            val mode = custom?.base ?: builtInTheme(preferences)
            val themeId = custom?.id ?: mode.stored
            val stored = preferences[themeLayoutKey(themeId)]
            val layout = NowPlayingLayout.entries.firstOrNull { it.stored == stored }
                ?: builtInThemeLayout(themeId)?.presentation ?: NowPlayingLayout.STANDARD
            return Appearance(
                theme = mode,
                nowPlayingLayout = layout,
                customTheme = custom,
                nowPlayingMoodBackground = preferences[MOOD_BACKGROUND] ?: true,
            )
        }
    }
}
