package io.github.lorddetson.piplayer.settings

import io.github.lorddetson.piplayer.settings.librarytabs.LibraryButton
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabWrite
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UiGesturePersistenceTest {
    private val surface = UiSurface.NOW_PLAYING
    private val target = UiGestureTarget.component("player:cover")
    private val preset = UiGesturePreset(
        "triangle", "Triangle", (0 until 32).map { index ->
            val position = index / 31f
            UiGesturePoint(position, if (position <= 0.5f) position * 2 else (1f - position) * 2)
        },
    )
    private val onlyPresets = UiGestureProfile(presets = listOf(preset))
    private val menuSlot = UiGestureSlot(target, UiGesture.builtIn(UiBuiltInGesture.LONG_PRESS))
    private val gestureSlot = UiGestureSlot(target, UiGesture.custom(preset.id))
    private val openMenu = UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = "player:cover")
    private val binding = UiGestureBinding(gestureSlot, openMenu, listOf(UiGestureMotionPreset.CARDS.timeline(target)))
    private val replacement = onlyPresets.editSurface(surface, UiSurfaceGestures().unbind(menuSlot).assign(binding))
    private val environment = UiGestureEnvironment(setOf("player:cover"), emptyList(), listOf(UiGestureBinding(menuSlot, openMenu)))

    @Test
    fun `only a gesture profile requires version ten and legacy versions reject injected gesture fields`() {
        val base = ThemePackage("Presets", ThemeMode.SYSTEM)
        val profileOnly = base.copy(canvases = UiThemeCanvases(gestures = onlyPresets))
        val withBindings = base.copy(canvases = UiThemeCanvases(gestures = replacement))
        for (theme in listOf(profileOnly, withBindings)) {
            val bytes = ThemePackageCodec.encode(theme)
            assertThat(bytes.decodeToString()).contains("\"version\":10")
            assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(ThemePackageRead.Loaded(theme))
            assertThat(ThemePackageCodec.decode(bytes.decodeToString().replace("\"version\":10", "\"version\":6").byteInputStream()))
                .isEqualTo(ThemePackageRead.Invalid)
        }
        val legacy = base.copy(canvases = UiThemeCanvases())
        val oldBytes = ThemePackageCodec.encode(legacy)
        assertThat(oldBytes.decodeToString()).contains("\"version\":6")
        assertThat(ThemePackageCodec.decode(oldBytes.inputStream())).isEqualTo(ThemePackageRead.Loaded(legacy))
        assertThat(ThemePackageCodec.decode(oldBytes.decodeToString().replace("\"version\":6", "\"version\":10").byteInputStream()))
            .isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `presets survive empty layout export and legacy layout updates are isolated by theme`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val settings = UiCanvasSettings(file)
        val appearance = AppearanceSettings(file)
        assertThat(settings.saveGestures(ThemeMode.SYSTEM.stored, surface, null, onlyPresets, environment)).isTrue()
        assertThat(settings.state.first().layouts).isEqualTo(UiThemeCanvases(gestures = onlyPresets))
        val theme = appearance.exportThemePackage { it.stored }
        assertThat(theme.canvases).isEqualTo(UiThemeCanvases(gestures = onlyPresets))
        assertThat(settings.save(ThemeMode.SYSTEM.stored, UiSurface.LIBRARY, UiCanvasLayout(emptyList()))).isTrue()
        assertThat(settings.state.first().layouts.gestures).isEqualTo(onlyPresets)
        appearance.setTheme(ThemeMode.DARK)
        assertThat(settings.state.first().layouts.gestures).isEqualTo(UiGestureProfile())
        assertThat(settings.saveGestures(ThemeMode.SYSTEM.stored, surface, null, replacement, environment)).isFalse()
        val imported = CustomTheme(name = "Imported gestures", base = ThemeMode.DARK)
        val applied = appearance.applyCustomTheme(imported, canvases = theme.canvases) as ThemeApplyResult.Applied
        assertThat(settings.state.first().layouts.gestures).isEqualTo(onlyPresets)
        assertThat(appearance.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)
        assertThat(settings.state.first().layouts.gestures).isEqualTo(UiGestureProfile())
        appearance.setTheme(ThemeMode.SYSTEM)
        assertThat(settings.state.first().layouts.gestures).isEqualTo(onlyPresets)
        assertThat(settings.state.first().layouts.panels).containsOnlyKeys(UiSurface.LIBRARY)
    }

    @Test
    fun `invalid replacement deletion changes neither the active profile nor the layout`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val settings = UiCanvasSettings(file)
        val themeId = ThemeMode.SYSTEM.stored
        assertThat(settings.saveGestures(themeId, surface, null, replacement, environment)).isTrue()
        val noReplacement = UiGestureProfile().editSurface(surface, UiSurfaceGestures().unbind(menuSlot))
        val hidden = UiCanvasLayout(emptyList(), hiddenStock = setOf("player:cover"))
        assertThat(settings.saveGestures(themeId, surface, hidden, noReplacement, environment.copy(components = emptySet())))
            .isFalse()
        assertThat(settings.state.first().layouts).isEqualTo(UiThemeCanvases(gestures = replacement))
        assertThat(settings.saveGestures(themeId, surface, null, UiGestureProfile(), environment, expected = onlyPresets))
            .isFalse()
        assertThat(settings.saveGestures(themeId, surface, null, UiGestureProfile(), environment, expected = replacement))
            .isTrue()
    }

    @Test
    fun `preset deletion on another surface cannot remove its remaining assignment`() = runBlocking<Unit> {
        val settings = UiCanvasSettings(MemoryDataStore())
        val themeId = ThemeMode.SYSTEM.stored
        assertThat(settings.saveGestures(themeId, surface, null, replacement, environment)).isTrue()
        assertThat(settings.saveGestures(themeId, UiSurface.MINI_PLAYER, null, UiGestureProfile(), environment)).isFalse()
        assertThat(settings.state.first().layouts.gestures).isEqualTo(replacement)
    }

    @Test
    fun `a new override cannot evade validation when its source is simultaneously hidden`() = runBlocking<Unit> {
        val settings = UiCanvasSettings(MemoryDataStore())
        val unbind = UiGestureProfile().editSurface(surface, UiSurfaceGestures().unbind(menuSlot))
        val hidden = UiCanvasLayout(emptyList(), hiddenStock = setOf("player:cover"))
        val changed = settings.saveGestures(ThemeMode.SYSTEM.stored, surface, hidden, unbind,
            environment.copy(components = emptySet()))
        assertThat(changed).isFalse()
        assertThat(settings.state.first().layouts.isEmpty).isTrue()
    }

    @Test
    fun `hiding a displaced stock source preserves its coverage requirement across later saves`() = runBlocking<Unit> {
        val settings = UiCanvasSettings(MemoryDataStore())
        val themeId = ThemeMode.SYSTEM.stored
        val another = UiGestureTarget.component("player:play")
        val alternate = binding.copy(slot = gestureSlot.copy(target = another), timelines = emptyList())
        val initial = onlyPresets.editSurface(surface, UiSurfaceGestures().unbind(menuSlot).assign(alternate))
        val active = environment.copy(components = environment.components + another.members)
        val hidden = UiCanvasLayout(emptyList(), hiddenStock = setOf("player:cover"))
        val stillReachable = active.copy(components = active.components - "player:cover")
        assertThat(settings.saveGestures(themeId, surface, null, initial, active)).isTrue()
        assertThat(settings.saveGestures(themeId, surface, hidden, initial, stillReachable)).isTrue()
        val afterHide = settings.state.first().layouts.gestures
        assertThat(afterHide.surfaces.getValue(surface).unbound).containsExactly(menuSlot)
        assertThat(settings.saveGestures(themeId, surface, hidden, onlyPresets, stillReachable)).isFalse()
        assertThat(settings.state.first().layouts.gestures).isEqualTo(afterHide)
    }

    @Test
    fun `preset names can change from any surface while shape changes need their active assignments`() = runBlocking<Unit> {
        val settings = UiCanvasSettings(MemoryDataStore())
        val themeId = ThemeMode.SYSTEM.stored
        assertThat(settings.saveGestures(themeId, surface, null, replacement, environment)).isTrue()
        val mirrored = preset.copy(points = preset.points.map { it.copy(x = 1f - it.x) })
        val renamed = preset.copy(name = "Renamed")
        assertThat(settings.saveGestures(themeId, UiSurface.MINI_PLAYER, null,
            replacement.copy(presets = listOf(mirrored)), environment)).isFalse()
        assertThat(settings.saveGestures(themeId, UiSurface.MINI_PLAYER, null,
            replacement.copy(presets = listOf(renamed)), environment)).isTrue()
        assertThat(settings.state.first().layouts.gestures.presets).containsExactly(renamed)
        assertThat(settings.saveGestures(themeId, surface, null, replacement.copy(presets = listOf(mirrored)), environment))
            .isTrue()
    }

    @Test
    fun `a legacy import inherits canvas geometry and starts with stock gestures`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val settings = UiCanvasSettings(file)
        val appearance = AppearanceSettings(file)
        val layout = UiCanvasLayout(listOf(UiCanvasItem("player:cover", UiCanvasBounds(0, 0, 500, 500))))
        assertThat(settings.saveGestures(ThemeMode.SYSTEM.stored, surface, layout, replacement, environment)).isTrue()
        val imported = CustomTheme(name = "Legacy theme", base = ThemeMode.DARK)
        val applied = appearance.applyCustomTheme(imported) as ThemeApplyResult.Applied
        assertThat(settings.state.first().layouts).isEqualTo(UiThemeCanvases(mapOf(surface to layout)))
        assertThat(appearance.undoCustomTheme(applied.undo)).isEqualTo(ThemeUndoResult.RESTORED)
        assertThat(settings.state.first().layouts).isEqualTo(UiThemeCanvases(mapOf(surface to layout), replacement))
    }

    @Test
    fun `workspace import checks the inherited library panel before selecting the theme`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val appearance = AppearanceSettings(file)
        assertThat(LibraryTabsSettings(file).setButtonVisible(LibraryButton.SEARCH.id, false, ThemeMode.SYSTEM.stored))
            .isEqualTo(LibraryTabWrite.STORED)
        val stock = UiGestureSlot(UiGestureTarget.component("tab:songs"), UiGesture.builtIn(UiBuiltInGesture.LONG_PRESS))
        val substitute = UiGestureSlot(UiGestureTarget.component("library:search"), UiGesture.builtIn(UiBuiltInGesture.SWIPE_UP))
        val action = UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = "tab:songs")
        val gestures = UiGestureProfile().editSurface(UiSurface.LIBRARY,
            UiSurfaceGestures().unbind(stock).assign(UiGestureBinding(substitute, action)))
        val workspace = UiCanvasLayout(emptyList(), hiddenStock = setOf("workspace:tabs"))
        val canvases = UiThemeCanvases(mapOf(UiSurface.LIBRARY_WORKSPACE to workspace), gestures)
        val imported = CustomTheme(name = "Workspace", base = ThemeMode.SYSTEM)
        assertThat(appearance.applyCustomTheme(imported, canvases = canvases)).isEqualTo(ThemeApplyResult.InvalidTheme)
        assertThat(UiCanvasSettings(file).state.first().themeId).isEqualTo(ThemeMode.SYSTEM.stored)
        assertThat(appearance.applyCustomTheme(imported, panel = LibraryPanelLayout(), canvases = canvases))
            .isInstanceOf(ThemeApplyResult.Applied::class.java)
    }
}
