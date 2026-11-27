package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.BottomNavigationDocument
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.MiniPlayerPanelDocument
import io.github.lorddetson.piplayer.settings.NowPlayingPanelDocument
import io.github.lorddetson.piplayer.settings.NowPlayingRow
import io.github.lorddetson.piplayer.settings.ThemeFontSelection
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.ThemePalette
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.bottomNavigationLayouts
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelDocument
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.settings.miniPlayerPanels
import io.github.lorddetson.piplayer.settings.nowPlayingPanels

internal object ConfigurationThemes {
    fun snapshot(preferences: Preferences): AppearancePatch {
        val custom = CustomThemesSettings.stateOf(preferences)
        return AppearancePatch(
            builtInTheme = AppearanceSettings.builtInTheme(preferences),
            activeTheme = custom.selectedId?.let { ActiveTheme.Custom(it) } ?: ActiveTheme.BuiltIn,
            nowPlayingMoodBackground = preferences[AppearanceSettings.MOOD_BACKGROUND] ?: true,
            builtInOverrides = ThemeMode.entries.mapNotNull { theme ->
                val overrides = layoutSnapshot(preferences, theme.stored)
                (theme to overrides).takeUnless { overrides == ThemeLayoutPatch() }
            }.toMap(),
            savedThemes = custom.themes.associate { theme ->
                theme.id to SavedThemePatch(
                    name = theme.name,
                    base = theme.base,
                    palette = PalettePatch(
                        accentRgb = Explicit(theme.palette.accentRgb),
                        backgroundRgb = Explicit(theme.palette.backgroundRgb),
                        surfaceRgb = Explicit(theme.palette.surfaceRgb),
                        textRgb = Explicit(theme.palette.textRgb),
                        automaticText = theme.palette.automaticText,
                    ),
                    font = theme.font.takeUnless { it is ThemeFontSelection.Imported },
                    layout = layoutSnapshot(preferences, theme.id),
                )
            },
        )
    }

    /** Stage the entire appearance patch before replacing the detached preferences. */
    fun apply(patch: AppearancePatch, preferences: MutablePreferences): Boolean {
        val candidate = preferences.toMutablePreferences()
        if (!merge(patch, candidate) || !isValidAfterChange(preferences, candidate)) return false
        if (preferences != candidate) {
            preferences.asMap().keys.minus(candidate.asMap().keys).forEach { preferences.remove(it) }
            preferences += candidate
        }
        return true
    }

    private fun merge(patch: AppearancePatch, preferences: MutablePreferences): Boolean {
        patch.builtInTheme?.takeUnless { it == AppearanceSettings.builtInTheme(preferences) }?.let {
            preferences[AppearanceSettings.THEME] = it.stored
        }
        patch.nowPlayingMoodBackground?.takeUnless { it == (preferences[AppearanceSettings.MOOD_BACKGROUND] ?: true) }?.let {
            preferences[AppearanceSettings.MOOD_BACKGROUND] = it
        }
        val saved = CustomThemesSettings.stateOf(preferences).themes.associateBy(CustomTheme::id)
        val oldEntries = CustomThemesSettings.storedThemes(preferences)
        val entries = oldEntries.toMutableMap()
        for ((id, change) in patch.savedThemes) {
            val previous = saved[id]
            val updated = mergeSavedTheme(id, previous, change) ?: return false
            if (updated != previous) entries[id] = CustomThemesSettings.themeEntry(entries[id], updated)
            if (!mergeLayout(preferences, id, change.layout)) return false
        }
        if (entries != oldEntries) CustomThemesSettings.storeThemeEntries(preferences, entries)
        for ((mode, layout) in patch.builtInOverrides) {
            if (!mergeLayout(preferences, mode.stored, layout)) return false
        }
        when (val active = patch.activeTheme) {
            null -> Unit
            ActiveTheme.BuiltIn -> if (preferences[CustomThemesSettings.SELECTED] != null) {
                CustomThemesSettings.selectTheme(preferences, null)
            }
            is ActiveTheme.Custom -> if (active.id != CustomThemesSettings.selectedTheme(preferences)?.id &&
                !CustomThemesSettings.selectTheme(preferences, active.id)
            ) return false
        }
        return true
    }

    fun isValidAfterChange(
        before: Preferences,
        after: Preferences,
        restoresLegacyState: (String) -> Boolean = { false },
    ): Boolean = affectedThemeIds(before, after).all { id -> isValid(after, id) || restoresLegacyState(id) }

    fun affectedThemeIds(before: Preferences, after: Preferences): List<String> {
        val beforeThemes = CustomThemesSettings.storedThemes(before)
        val afterThemes = CustomThemesSettings.storedThemes(after)
        val afterActive = AppearanceSettings.panelThemeId(after)
        val beforeActive = AppearanceSettings.panelThemeId(before)
        val beforeFallback = AppearanceSettings.builtInTheme(before)
        val afterFallback = AppearanceSettings.builtInTheme(after)
        val knownThemes = ThemeMode.entries.map(ThemeMode::stored) + afterThemes.keys
        return knownThemes.filter { id ->
            beforeThemes[id] != afterThemes[id] ||
                id == afterActive && afterActive != beforeActive ||
                id == afterFallback.stored && afterFallback != beforeFallback ||
                layoutKeys(id).any { before[it] != after[it] }
        }
    }

    fun isValid(preferences: Preferences, themeId: String): Boolean {
        val canvases = UiCanvasSettings.validatedLayouts(preferences, themeId) ?: return false
        return validateConfigurationLayout(
            canvases = canvases,
            library = LibraryTabsSettings.panelOf(preferences, themeId),
            player = nowPlayingPanels.panelOf(preferences, themeId),
            miniPlayer = miniPlayerPanels.panelOf(preferences, themeId),
            navigation = bottomNavigationLayouts.panelOf(preferences, themeId),
        )
    }

    fun layoutKeys(themeId: String): List<Preferences.Key<String>> = listOf(
        AppearanceSettings.themeLayoutKey(themeId), LibraryTabsSettings.panelKey(themeId), nowPlayingPanels.key(themeId),
        miniPlayerPanels.key(themeId), bottomNavigationLayouts.key(themeId), UiCanvasSettings.key(themeId),
    )

    private fun layoutSnapshot(preferences: Preferences, themeId: String): ThemeLayoutPatch {
        val canvases = UiCanvasSettings.savedLayouts(preferences, themeId)
        return ThemeLayoutPatch(
            presentation = AppearanceSettings.savedPresentation(preferences, themeId)?.let(::Explicit),
            library = LibraryTabsSettings.savedPanel(preferences, themeId)?.let(::Explicit),
            player = nowPlayingPanels.savedPanel(preferences, themeId)?.let(::Explicit),
            miniPlayer = miniPlayerPanels.savedPanel(preferences, themeId)?.let(::Explicit),
            navigation = bottomNavigationLayouts.savedPanel(preferences, themeId)?.let(::Explicit),
            canvases = canvases.panels.mapValues { Explicit(it.value) },
            gestures = canvases.gestures.takeUnless(UiGestureProfile::isEmpty)?.let(::Explicit),
        )
    }

    private fun mergeSavedTheme(id: String, old: CustomTheme?, change: SavedThemePatch): CustomTheme? {
        if (change.name?.let(::validConfigurationThemeName) == false || change.font is ThemeFontSelection.Imported) return null
        val name = change.name ?: old?.name ?: return null
        val base = change.base ?: old?.base ?: return null
        val font = change.font ?: old?.font ?: ThemeFontSelection.System
        val palette = old?.palette ?: ThemePalette()
        val color = change.palette
        return CustomTheme(
            name, base,
            palette.copy(
                accentRgb = color.accentRgb.merge(palette.accentRgb),
                backgroundRgb = color.backgroundRgb.merge(palette.backgroundRgb),
                surfaceRgb = color.surfaceRgb.merge(palette.surfaceRgb),
                textRgb = color.textRgb.merge(palette.textRgb),
                automaticText = color.automaticText ?: palette.automaticText,
            ),
            font, id,
        ).takeIf(CustomThemesSettings::isValid)
    }

    private fun mergeLayout(preferences: MutablePreferences, themeId: String, change: ThemeLayoutPatch): Boolean {
        if (change.library?.value?.let { LibraryPanelDocument.from(it).toLayout() != it } == true ||
            change.player?.value?.let {
                it.rows.size != NowPlayingRow.entries.size ||
                    NowPlayingPanelDocument.from(it).toPanel() != it
            } == true ||
            change.miniPlayer?.value?.let { MiniPlayerPanelDocument.from(it).toPanel() != it } == true ||
            change.navigation?.value?.let { BottomNavigationDocument.from(it).toLayout() != it } == true
        ) return false

        val old = layoutSnapshot(preferences, themeId)
        change.presentation?.takeUnless { it == old.presentation }?.let {
            val key = AppearanceSettings.themeLayoutKey(themeId)
            val value = it.value
            if (value == null) preferences.remove(key) else preferences[key] = value.stored
        }
        change.library?.takeUnless { it == old.library }?.let { LibraryTabsSettings.storePanel(preferences, themeId, it.value) }
        change.player?.takeUnless { it == old.player }?.let { nowPlayingPanels.storePanel(preferences, themeId, it.value) }
        change.miniPlayer?.takeUnless { it == old.miniPlayer }?.let { miniPlayerPanels.storePanel(preferences, themeId, it.value) }
        change.navigation?.takeUnless { it == old.navigation }?.let { bottomNavigationLayouts.storePanel(preferences, themeId, it.value) }

        if (change.canvases.isEmpty() && change.gestures == null) return true
        val canvasBefore = UiCanvasSettings.savedLayouts(preferences, themeId)
        val updatedPanels = canvasBefore.panels.toMutableMap()
        for ((surface, entry) in change.canvases) {
            val replacement = entry.value
            if (replacement == null) updatedPanels.remove(surface) else updatedPanels[surface] = replacement
        }
        val gestures = change.gestures.merge(canvasBefore.gestures) ?: UiGestureProfile()
        val updated = canvasBefore.copy(panels = updatedPanels, gestures = gestures)
        if (!updated.isValid) return false
        if (updated != canvasBefore) UiCanvasSettings.store(preferences, themeId, updated)
        return true
    }

    private fun <T : Any> Explicit<T>?.merge(previous: T?): T? = if (this == null) previous else value
}
