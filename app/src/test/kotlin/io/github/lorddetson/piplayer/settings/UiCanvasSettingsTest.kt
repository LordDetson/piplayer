package io.github.lorddetson.piplayer.settings

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UiCanvasSettingsTest {
    private val cover = UiCanvasItem("now:cover", UiCanvasBounds(100, 100, 500, 500))
    private val play = UiCanvasItem("now:play_pause", UiCanvasBounds(640, 200, 180, 180))
    private val player = UiCanvasLayout(listOf(cover, play))

    @Test
    fun `a component can overlap another and move between layers`() {
        val moved = player.move(play.id, -540, -100)
        assertThat(moved.items.last().bounds).isEqualTo(UiCanvasBounds(100, 100, 180, 180))
        assertThat(moved.raise(cover.id).items.map(UiCanvasItem::id)).containsExactly(play.id, cover.id)
        assertThat(moved.lower(play.id).items.map(UiCanvasItem::id)).containsExactly(play.id, cover.id)
        assertThat(moved.hide(cover.id).items).containsExactly(moved.items.last())
    }

    @Test
    fun `movement and resizing preserve valid bounds and independent padding`() {
        val positioned = player.move(play.id, 1000, -1000)
            .resize(play.id, -140, 25)
            .withPadding(play.id, UiCanvasPadding(4, 8, 12, 16))
        assertThat(positioned.items.last()).isEqualTo(play.copy(
            bounds = UiCanvasBounds(820, 0, 40, 205),
            padding = UiCanvasPadding(4, 8, 12, 16),
        ))
        assertThat(positioned.isValid).isTrue()
        assertThat(positioned.add(play)).isEqualTo(positioned)
        assertThat(positioned.add(UiCanvasItem("nav:library", UiCanvasBounds(0, 600, 200, 400))).items)
            .hasSize(3)
    }

    @Test
    fun `alignment finds nearby edges while leaving distant positions free`() {
        assertThat(player.move(play.id, -537, -97).snap(play.id).items.last().bounds)
            .isEqualTo(UiCanvasBounds(100, 100, 180, 180))
        assertThat(player.move(play.id, -452, -50).snap(play.id).items.last().bounds)
            .isEqualTo(UiCanvasBounds(188, 150, 180, 180))
    }

    @Test
    fun `theme changes and resets keep saved layers for each surface independently`() = runBlocking<Unit> {
        val file = MemoryDataStore()
        val appearance = AppearanceSettings(file)
        val canvases = UiCanvasSettings(file)
        val library = UiCanvasLayout(listOf(UiCanvasItem("nav:fix", UiCanvasBounds(5, 7, 200, 400))))
        assertThat(canvases.save(ThemeMode.SYSTEM.stored, UiSurface.NOW_PLAYING, player)).isTrue()
        assertThat(canvases.save(ThemeMode.SYSTEM.stored, UiSurface.LIBRARY, library)).isTrue()
        appearance.setTheme(ThemeMode.DARK)
        assertThat(canvases.save(ThemeMode.SYSTEM.stored, UiSurface.NOW_PLAYING, null)).isFalse()
        assertThat(canvases.save(ThemeMode.DARK.stored, UiSurface.NOW_PLAYING, player.hide(cover.id))).isTrue()
        appearance.setTheme(ThemeMode.SYSTEM)
        assertThat(UiCanvasSettings.layoutsOf(file.data.first()).panels[UiSurface.NOW_PLAYING]).isEqualTo(player)
        assertThat(canvases.save(ThemeMode.SYSTEM.stored, UiSurface.NOW_PLAYING, null)).isTrue()
        assertThat(UiCanvasSettings.layoutsOf(file.data.first()).panels).containsOnlyKeys(UiSurface.LIBRARY)
        appearance.setTheme(ThemeMode.DARK)
        assertThat(UiCanvasSettings.layoutsOf(file.data.first()).panels[UiSurface.NOW_PLAYING]).isEqualTo(player.hide(cover.id))
    }

    @Test
    fun `theme files transfer canvas geometry and reject invalid dimensions`() {
        val icon = UiIconAsset(UiIconFormat.SVG, """<svg viewBox="0 0 10 10"><path d="M1 1L9 5L1 9Z"/></svg>""")
        val styled = player.withStyle(play.id, UiControlStyle(0x301077, 0xffffff, icon, UiControlMotion.BOUNCE))
        val theme = ThemePackage("My player", ThemeMode.DARK, canvases = UiThemeCanvases(mapOf(UiSurface.NOW_PLAYING to styled)))
        val bytes = ThemePackageCodec.encode(theme)
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(ThemePackageRead.Loaded(theme))
        val broken = bytes.decodeToString().replace("\"width\":500", "\"width\":2000")
        assertThat(broken).isNotEqualTo(bytes.decodeToString())
        assertThat(ThemePackageCodec.decode(broken.byteInputStream())).isEqualTo(ThemePackageRead.Invalid)
    }
}
