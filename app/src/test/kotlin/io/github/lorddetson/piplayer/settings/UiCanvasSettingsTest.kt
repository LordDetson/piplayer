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
    fun `changes to a native layout save only deliberate overrides`() {
        val stockIds = setOf(cover.id, play.id, "now:next")
        val onlyCoverHidden = player.hide(cover.id).stockOverrides(player, previous = null, stockIds)
        assertThat(onlyCoverHidden).isEqualTo(UiCanvasLayout(emptyList(), hiddenStock = setOf(cover.id)))
        val moved = player.move(play.id, 55, 5)
        val savedMove = moved.stockOverrides(player, previous = null, stockIds)
        assertThat(savedMove).isEqualTo(UiCanvasLayout(listOf(moved.items.last()), hiddenStock = emptySet()))
        assertThat(moved.stockOverrides(moved, savedMove, stockIds)).isEqualTo(savedMove)
        assertThat(player.stockOverrides(player, previous = null, stockIds)).isNull()
        assertThat(player.raise(cover.id).stockOverrides(player, previous = null, stockIds)?.items?.map(UiCanvasItem::id))
            .containsExactly(play.id, cover.id)
        val theme = ThemePackage("Hide cover", ThemeMode.SYSTEM,
            canvases = UiThemeCanvases(mapOf(UiSurface.NOW_PLAYING to checkNotNull(onlyCoverHidden))))
        val packageData = ThemePackageCodec.encode(theme)
        assertThat(packageData.decodeToString()).contains("\"version\":7")
        assertThat(ThemePackageCodec.decode(packageData.inputStream())).isEqualTo(ThemePackageRead.Loaded(theme))
    }

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
    fun `a group moves scales changes layer and hides as one selection`() {
        val selected = setOf(cover.id, play.id)
        val next = UiCanvasItem("now:next", UiCanvasBounds(840, 100, 100, 100))
        val grouped = player.add(next).group(selected)
        assertThat(grouped.members(play.id)).isEqualTo(selected)
        assertThat(grouped.move(play.id, 500, -150).boundsOf(selected))
            .isEqualTo(UiCanvasBounds(280, 0, 720, 500))
        val scaled = grouped.resize(play.id, UiResizeEdge.TOP_LEFT, -72, -50)
        assertThat(scaled.boundsOf(selected)).isEqualTo(UiCanvasBounds(28, 50, 792, 550))
        assertThat(scaled.items[0].bounds).isEqualTo(UiCanvasBounds(28, 50, 550, 550))
        assertThat(scaled.items[1].bounds).isEqualTo(UiCanvasBounds(622, 160, 198, 198))
        assertThat(scaled.raise(cover.id).items.map(UiCanvasItem::id)).containsExactly(next.id, cover.id, play.id)
        assertThat(scaled.hide(play.id).items).containsExactly(next)
        assertThat(scaled.ungroup(play.id).hide(play.id).items).containsExactly(scaled.items[0], next)
    }

    @Test
    fun `an edge holds opposite edges fixed and snaps only the edge dragged`() {
        val resized = player.resize(play.id, UiResizeEdge.LEFT, -37, 120)
        assertThat(resized.items.last().bounds).isEqualTo(UiCanvasBounds(603, 200, 217, 180))
        assertThat(resized.snapResize(play.id, UiResizeEdge.LEFT).items.last().bounds)
            .isEqualTo(UiCanvasBounds(600, 200, 220, 180))
        assertThat(resized.copy(snapping = false).snapResize(play.id, UiResizeEdge.LEFT))
            .isEqualTo(resized.copy(snapping = false))
        val grouped = player.group(setOf(cover.id, play.id)).move(play.id, -97, -96)
        assertThat(grouped.snap(play.id).boundsOf(grouped.members(play.id)))
            .isEqualTo(UiCanvasBounds(0, 0, 720, 500))
        assertThat(grouped.copy(snapping = false).snap(play.id)).isEqualTo(grouped.copy(snapping = false))
    }

    @Test
    fun `pushing moves touching neighbors and their groups while leaving independent items fixed`() {
        val first = UiCanvasItem("test:first", UiCanvasBounds(0, 0, 200, 200))
        val second = UiCanvasItem("test:second", UiCanvasBounds(200, 0, 200, 200))
        val third = UiCanvasItem("test:third", UiCanvasBounds(400, 0, 200, 200))
        val fourth = UiCanvasItem("test:fourth", UiCanvasBounds(425, 200, 150, 200))
        val independent = UiCanvasItem("test:independent", UiCanvasBounds(0, 600, 100, 100))
        val layout = UiCanvasLayout(listOf(first, second, third, fourth, independent))
            .group(setOf(third.id, fourth.id)).withPlacement(UiPlacementMode.PUSH)
        val moved = layout.move(first.id, 100, 0)
        assertThat(moved.items.map { it.bounds.x }).containsExactly(100, 300, 500, 525, 0)
        val resized = moved.resize(first.id, UiResizeEdge.RIGHT, 50, 0)
        assertThat(resized.items.map { it.bounds.x }).containsExactly(100, 350, 550, 575, 0)
    }

    @Test
    fun `pushing keeps the last valid layout when there is no room and mode change is atomic`() {
        val left = UiCanvasItem("test:left", UiCanvasBounds(0, 0, 500, 1000))
        val right = UiCanvasItem("test:right", UiCanvasBounds(500, 0, 500, 1000))
        val layout = UiCanvasLayout(listOf(left, right)).withPlacement(UiPlacementMode.PUSH)
        assertThat(layout.move(left.id, 10, 0)).isEqualTo(layout)
        assertThat(layout.resize(left.id, UiResizeEdge.RIGHT, 10, 0)).isEqualTo(layout)
        assertThat(layout.add(UiCanvasItem("test:full", UiCanvasBounds(0, 0, 1000, 1000)))).isEqualTo(layout)
        val overlapping = UiCanvasLayout(listOf(left.copy(bounds = UiCanvasBounds(0, 0, 600, 1000)), right))
        assertThat(overlapping.withPlacement(UiPlacementMode.PUSH)).isEqualTo(overlapping)
        val recoverable = UiCanvasLayout(listOf(left, right.copy(bounds = UiCanvasBounds(490, 0, 500, 1000))))
        assertThat(recoverable.withPlacement(UiPlacementMode.PUSH)).isEqualTo(layout)
    }

    @Test
    fun `a sparse theme package transfers groups preferences and text alignment`() {
        val selected = setOf(cover.id, play.id)
        val grouped = player.group(selected).copy(snapping = false, placement = UiPlacementMode.PUSH)
            .withStyle(play.id, UiControlStyle(textAlignment = UiTextAlignment.END))
        val sparse = checkNotNull(grouped.stockOverrides(player, previous = null, selected))
        assertThat(sparse.items.map(UiCanvasItem::id)).containsExactly(play.id)
        assertThat(sparse.members(play.id)).isEqualTo(selected)
        assertThat(sparse.isValid).isTrue()
        val theme = ThemePackage("Grouped player", ThemeMode.DARK,
            canvases = UiThemeCanvases(mapOf(UiSurface.NOW_PLAYING to sparse)))
        val bytes = ThemePackageCodec.encode(theme)
        assertThat(bytes.decodeToString()).contains("\"version\":8")
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(ThemePackageRead.Loaded(theme))
        assertThat(ThemePackageCodec.decode(bytes.decodeToString().replace("\"version\":8", "\"version\":7").byteInputStream()))
            .isEqualTo(ThemePackageRead.Invalid)

        val sampled = sparse.copy(hiddenStock = null)
        assertThat(sampled.move(play.id, 10, 0)).isEqualTo(sampled)
        val hidden = sampled.hide(play.id).stockOverrides(sampled, sparse, selected)
        assertThat(hidden?.hiddenStock).isEqualTo(selected)
        assertThat(hidden?.groups).isEmpty()
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
