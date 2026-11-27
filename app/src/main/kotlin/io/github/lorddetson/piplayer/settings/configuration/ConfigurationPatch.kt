package io.github.lorddetson.piplayer.settings.configuration

import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinition
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.BottomNavigationLayout
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.MiniPlayerPanel
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.NowPlayingPanel
import io.github.lorddetson.piplayer.settings.ThemeFontSelection
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset

internal data class ConfigurationPatch(
    val language: AppLanguage? = null,
    val library: LibraryPatch = LibraryPatch(),
    val tagWriting: TagWritingPatch = TagWritingPatch(),
    val sleepTimer: SleepTimerPatch = SleepTimerPatch(),
    val appearance: AppearancePatch = AppearancePatch(),
)

internal data class LibraryPatch(
    val showSystemSounds: Boolean? = null,
    val showRecordings: Boolean? = null,
    val minLengthSeconds: Int? = null,
    val legacyEncodingId: String? = null,
    val lists: Map<ListName, ListPatch> = emptyMap(),
    val customTabs: Map<String, CustomTabDefinition> = emptyMap(),
    val customTabOrder: List<String>? = null,
)

internal data class ListPatch(val field: String? = null, val reversed: Boolean? = null, val grid: Boolean? = null)
internal data class TagWritingPatch(val id3v1: Id3v1WritePolicy? = null, val cover: TagCoverPreset? = null)
internal data class SleepTimerPatch(val hours: Int? = null, val minutes: Int? = null, val finishTrack: Boolean? = null)

internal data class AppearancePatch(
    val builtInTheme: ThemeMode? = null,
    val activeTheme: ActiveTheme? = null,
    val nowPlayingMoodBackground: Boolean? = null,
    val builtInOverrides: Map<ThemeMode, ThemeLayoutPatch> = emptyMap(),
    val savedThemes: Map<String, SavedThemePatch> = emptyMap(),
)

internal sealed interface ActiveTheme {
    data object BuiltIn : ActiveTheme
    data class Custom(val id: String) : ActiveTheme
}

internal data class SavedThemePatch(
    val name: String? = null,
    val base: ThemeMode? = null,
    val palette: PalettePatch = PalettePatch(),
    val font: ThemeFontSelection? = null,
    val layout: ThemeLayoutPatch = ThemeLayoutPatch(),
)

internal data class ThemeLayoutPatch(
    val presentation: Explicit<NowPlayingLayout>? = null,
    val library: Explicit<LibraryPanelLayout>? = null,
    val player: Explicit<NowPlayingPanel>? = null,
    val miniPlayer: Explicit<MiniPlayerPanel>? = null,
    val navigation: Explicit<BottomNavigationLayout>? = null,
    val canvases: Map<UiSurface, Explicit<UiCanvasLayout>> = emptyMap(),
    val gestures: Explicit<UiGestureProfile>? = null,
)

/** An omitted patch leaves the value alone, while an explicit null removes its stored override. */
internal data class Explicit<T : Any>(val value: T?)

internal data class PalettePatch(
    val accentRgb: Explicit<Int>? = null,
    val backgroundRgb: Explicit<Int>? = null,
    val surfaceRgb: Explicit<Int>? = null,
    val textRgb: Explicit<Int>? = null,
    val automaticText: Boolean? = null,
)
