package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ThemeFontSelection
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.ThemePalette
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiControlStyle
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiIconAsset
import io.github.lorddetson.piplayer.settings.UiIconFormat
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigurationThemesTest {
    @Test
    fun `export includes inactive overrides saved themes and remembered fallback`() {
        val preferences = mutablePreferencesOf()
        val patch = AppearancePatch(
            builtInTheme = ThemeMode.DARK,
            activeTheme = ActiveTheme.Custom(ID),
            builtInOverrides = mapOf(ThemeMode.LIGHT to ThemeLayoutPatch(presentation = Explicit(NowPlayingLayout.EXPANDED))),
            savedThemes = mapOf(ID to SavedThemePatch("Night", ThemeMode.AMOLED)),
        )
        assertThat(ConfigurationThemes.apply(patch, preferences)).isTrue()
        val snapshot = ConfigurationThemes.snapshot(preferences)
        assertThat(snapshot.builtInTheme).isEqualTo(ThemeMode.DARK)
        assertThat(snapshot.activeTheme).isEqualTo(ActiveTheme.Custom(ID))
        assertThat(snapshot.builtInOverrides.getValue(ThemeMode.LIGHT).presentation).isEqualTo(Explicit(NowPlayingLayout.EXPANDED))
        val fresh = mutablePreferencesOf()
        assertThat(ConfigurationThemes.apply(snapshot, fresh)).isTrue()
        assertThat(ConfigurationThemes.snapshot(fresh)).isEqualTo(snapshot)
    }

    @Test
    fun `omissions preserve the imported font and explicit resets remove only the addressed unit`() {
        val preferences = mutablePreferencesOf()
        val imported = ThemeFontSelection.Imported("$ID.otf", "Local font")
        CustomThemesSettings.storeTheme(preferences, CustomTheme(
            name = "Warm", base = ThemeMode.LIGHT, palette = ThemePalette(accentRgb = 123, textRgb = 456),
            font = imported, id = ID,
        ), select = true)
        preferences[AppearanceSettings.themeLayoutKey(ID)] = NowPlayingLayout.EXPANDED.stored
        val change = SavedThemePatch(palette = PalettePatch(accentRgb = Explicit(null)),
            layout = ThemeLayoutPatch(presentation = Explicit(null)))
        assertThat(ConfigurationThemes.apply(AppearancePatch(savedThemes = mapOf(ID to change)), preferences)).isTrue()
        assertThat(CustomThemesSettings.selectedTheme(preferences)).isEqualTo(
            CustomTheme("Warm", ThemeMode.LIGHT, ThemePalette(textRgb = 456), imported, ID),
        )
        assertThat(preferences[AppearanceSettings.themeLayoutKey(ID)]).isNull()
        val snapshot = ConfigurationThemes.snapshot(preferences)
        assertThat(snapshot.savedThemes.getValue(ID).font).isNull()
        val fresh = mutablePreferencesOf()
        assertThat(ConfigurationThemes.apply(snapshot, fresh)).isTrue()
        assertThat(CustomThemesSettings.selectedTheme(fresh)?.font).isEqualTo(ThemeFontSelection.System)
    }

    @Test
    fun `new themes require identity fields and active custom ids must exist`() {
        val preferences = mutablePreferencesOf()
        assertThat(ConfigurationThemes.apply(AppearancePatch(savedThemes = mapOf(ID to SavedThemePatch(name = "Night"))),
            preferences)).isFalse()
        assertThat(ConfigurationThemes.apply(AppearancePatch(activeTheme = ActiveTheme.Custom(ID)), preferences)).isFalse()
        assertThat(preferences.asMap()).isEmpty()
    }

    @Test
    fun `hiding the workspace strip cannot remove the merged replacement of a stock gesture`() {
        val preferences = mutablePreferencesOf()
        val stock = UiGestureSlot(UiGestureTarget.component("tab:songs"), UiGesture.builtIn(UiBuiltInGesture.TAP))
        val replacement = UiGestureBinding(
            UiGestureSlot(UiGestureTarget.component("tab:albums"), UiGesture.builtIn(UiBuiltInGesture.DOUBLE_TAP)),
            UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = "tab:songs"),
        )
        val gestures = UiGestureProfile(surfaces = mapOf(UiSurface.LIBRARY to UiSurfaceGestures(listOf(replacement), setOf(stock))))
        fun apply(layout: ThemeLayoutPatch) = ConfigurationThemes.apply(AppearancePatch(
            builtInOverrides = mapOf(ThemeMode.DARK to layout),
        ), preferences)

        assertThat(apply(ThemeLayoutPatch(gestures = Explicit(gestures)))).isTrue()
        assertThat(apply(ThemeLayoutPatch(canvases = mapOf(
            UiSurface.LIBRARY_WORKSPACE to Explicit(UiCanvasLayout(emptyList(), hiddenStock = setOf("workspace:tabs"))),
        )))).isFalse()
    }

    @Test
    fun `unknown component and unreachable playback replacement are rejected`() {
        val preferences = mutablePreferencesOf()
        fun apply(layout: ThemeLayoutPatch) = ConfigurationThemes.apply(AppearancePatch(
            builtInOverrides = mapOf(ThemeMode.SYSTEM to layout),
        ), preferences)
        val unknown = UiCanvasItem("now:missing", UiCanvasBounds(0, 0, 100, 100))
        assertThat(apply(ThemeLayoutPatch(canvases = mapOf(
            UiSurface.NOW_PLAYING to Explicit(UiCanvasLayout(listOf(unknown))),
        )))).isFalse()

        val next = UiGestureSlot(UiGestureTarget.component("now:cover"), UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT))
        val replacement = UiGestureBinding(
            UiGestureSlot(UiGestureTarget.component("now:play_pause"), UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT)),
            UiGestureAction(UiGestureActionId.NEXT_TRACK),
        )
        val gestures = UiGestureProfile(surfaces = mapOf(UiSurface.NOW_PLAYING to UiSurfaceGestures(listOf(replacement), setOf(next))))
        assertThat(apply(ThemeLayoutPatch(gestures = Explicit(gestures)))).isTrue()
        assertThat(apply(ThemeLayoutPatch(canvases = mapOf(
            UiSurface.NOW_PLAYING to Explicit(UiCanvasLayout(emptyList(), hiddenStock = setOf("now:play_pause"))),
        )))).isFalse()
    }

    @Test
    fun `disjoint canvas edits cannot exceed the merged theme asset budget`() {
        val icon = UiIconAsset(UiIconFormat.PNG, "x".repeat(13_000))
        fun layout(vararg ids: String) = UiCanvasLayout(ids.map {
            UiCanvasItem(it, UiCanvasBounds(0, 0, 100, 100), style = UiControlStyle(icon = icon))
        })
        val library = UiThemeCanvases(mapOf(UiSurface.LIBRARY to layout(
            "tab:songs", "tab:artists", "tab:albums", "tab:genres", "nav:library", "nav:search", "nav:fix", "library:sort",
        )))
        val player = UiThemeCanvases(mapOf(UiSurface.NOW_PLAYING to layout(
            "now:cover", "now:more", "now:queue", "now:seek", "now:format", "now:next", "now:previous", "now:shuffle",
        )))
        assertThat(library.isValid && player.isValid).isTrue()
        val before = mutablePreferencesOf()
        val after = mutablePreferencesOf()
        val theme = ThemeMode.SYSTEM.stored
        UiCanvasSettings.store(before, theme, library)
        UiCanvasSettings.store(after, theme, library.copy(panels = library.panels + player.panels))

        assertThat(ConfigurationThemes.isValidAfterChange(before, after)).isFalse()
    }

    @Test
    fun `workspace toolbar masking includes the gesture on its title`() {
        val title = UiGestureTarget.component("library:title")
        val binding = UiGestureBinding(UiGestureSlot(title, UiGesture.builtIn(UiBuiltInGesture.DOUBLE_TAP)),
            UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = "tab:songs"))
        val profile = UiGestureProfile(surfaces = mapOf(UiSurface.LIBRARY to UiSurfaceGestures(listOf(binding))))
        val preferences = mutablePreferencesOf()
        fun apply(layout: ThemeLayoutPatch) = ConfigurationThemes.apply(AppearancePatch(
            builtInOverrides = mapOf(ThemeMode.SYSTEM to layout),
        ), preferences)

        assertThat(apply(ThemeLayoutPatch(gestures = Explicit(profile)))).isTrue()
        val workspace = UiCanvasLayout(emptyList(), hiddenStock = setOf("workspace:toolbar"))
        assertThat(apply(ThemeLayoutPatch(canvases = mapOf(UiSurface.LIBRARY_WORKSPACE to Explicit(workspace))))).isFalse()
    }

    private companion object {
        const val ID = "2bcdf628-39bf-46dd-8708-277e17d04a70"
    }
}
