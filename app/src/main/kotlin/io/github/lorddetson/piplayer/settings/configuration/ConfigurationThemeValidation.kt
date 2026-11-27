package io.github.lorddetson.piplayer.settings.configuration

import io.github.lorddetson.piplayer.settings.BottomDestination
import io.github.lorddetson.piplayer.settings.BottomNavigationLayout
import io.github.lorddetson.piplayer.settings.MiniPlayerElement
import io.github.lorddetson.piplayer.settings.MiniPlayerPanel
import io.github.lorddetson.piplayer.settings.NowPlayingElement
import io.github.lorddetson.piplayer.settings.NowPlayingPanel
import io.github.lorddetson.piplayer.settings.NowPlayingRow
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import io.github.lorddetson.piplayer.settings.librarytabs.BuiltInLibraryTab
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryButton
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import io.github.lorddetson.piplayer.ui.customization.stockGesture
import io.github.lorddetson.piplayer.ui.customization.workspaceSlotFor
import io.github.lorddetson.piplayer.ui.player.AudioFormatField
import io.github.lorddetson.piplayer.ui.player.TrackField
import io.github.lorddetson.piplayer.ui.player.canvasId
import io.github.lorddetson.piplayer.ui.player.nextCoverGesture
import io.github.lorddetson.piplayer.ui.player.previousCoverGesture
import io.github.lorddetson.piplayer.ui.player.queueCoverGesture

private val workspaceBlocks = setOf(
    "workspace:toolbar", "workspace:tabs", "workspace:content", "workspace:mini_player", "workspace:navigation",
)
private val libraryTabIds = BuiltInLibraryTab.ids.mapTo(mutableSetOf()) { "tab:$it" }
private val libraryButtonIds = LibraryButton.ids.mapTo(mutableSetOf()) { "library:$it" }
private val navigationIds = BottomDestination.entries.mapTo(mutableSetOf()) { "nav:${it.id}" }
private val nowPlayingIds = NowPlayingElement.entries.mapTo(mutableSetOf(), NowPlayingElement::canvasId)
private val nowTrackIds = TrackField.entries.mapTo(mutableSetOf(), TrackField::nowPlayingId)
private val nowAudioIds = AudioFormatField.entries.mapTo(mutableSetOf(), AudioFormatField::canvasId) + "now:audio_badge"
private val miniIds = MiniPlayerElement.entries.mapTo(mutableSetOf()) { "mini:${it.id}" }
private val miniTrackIds = TrackField.entries.mapTo(mutableSetOf(), TrackField::miniPlayerId)
private val globalClicks = navigationIds + setOf(
    "library:settings", "now:queue", "now:previous", "now:play_pause", "now:next", "now:shuffle", "now:repeat",
    "now:timer", "now:stop_after", "now:equalizer",
)
private val nowClicks = NowPlayingElement.entries.filter { it.initialRow != NowPlayingRow.CONTENT }
    .map(NowPlayingElement::canvasId) + listOf(TrackField.ARTIST.nowPlayingId, TrackField.ALBUM.nowPlayingId)

private data class SurfaceComponents(val catalog: Set<String>, val native: Set<String>, val clicks: Set<String> = emptySet())

/** Checks the same stock and placed component spaces used by the panel editor after inheritance and workspace masking. */
internal fun validateConfigurationLayout(
    canvases: UiThemeCanvases,
    library: LibraryPanelLayout,
    player: NowPlayingPanel,
    miniPlayer: MiniPlayerPanel,
    navigation: BottomNavigationLayout,
): Boolean {
    if (!canvases.isValid) return false

    val workspace = effectiveComponents(workspaceBlocks, canvases.panels[UiSurface.LIBRARY_WORKSPACE])
    for (surface in UiSurface.entries) {
        val source = when (surface) {
            UiSurface.LIBRARY -> {
                val tabs = library.tabs.visible.map { "tab:$it" }
                val buttons = library.buttons.visible.map { "library:$it" }
                SurfaceComponents(libraryTabIds + libraryButtonIds + "library:title", (tabs + buttons).toSet() + "library:title",
                    libraryTabIds + libraryButtonIds)
            }
            UiSurface.NOW_PLAYING -> SurfaceComponents(
                nowPlayingIds + nowTrackIds + nowAudioIds,
                NowPlayingElement.entries.filterNot(player.hidden::contains).flatMap {
                    when (it) {
                        NowPlayingElement.TRACK_INFO -> nowTrackIds + it.canvasId
                        NowPlayingElement.FORMAT -> nowAudioIds + it.canvasId
                        else -> setOf(it.canvasId)
                    }
                }.toSet(),
                nowClicks.toSet(),
            )
            UiSurface.MINI_PLAYER -> SurfaceComponents(
                miniIds + miniTrackIds,
                (miniPlayer.visible + listOf(MiniPlayerElement.PROGRESS).filterNot(miniPlayer.hidden::contains)).flatMap {
                    if (it == MiniPlayerElement.TRACK_INFO) miniTrackIds + "mini:${it.id}" else setOf("mini:${it.id}")
                }.toSet(),
                miniTrackIds + setOf("mini:cover", "mini:track_info", "mini:play_pause"),
            )
            UiSurface.BOTTOM_NAVIGATION -> SurfaceComponents(
                navigationIds, navigation.visible.mapTo(mutableSetOf()) { "nav:${it.id}" }, navigationIds,
            )
            UiSurface.LIBRARY_WORKSPACE -> SurfaceComponents(workspaceBlocks, workspaceBlocks)
        }
        val known = source.catalog + globalClicks
        val layout = canvases.panels[surface]
        if (layout != null && !known.containsAll(
                layout.items.map(UiCanvasItem::id) + layout.hiddenStock.orEmpty() + layout.groups.flatten() + layout.labelDisplays.keys,
            )
        ) return false

        val profile = canvases.gestures.surfaces[surface] ?: continue
        val visible = effectiveComponents(source.native, layout).filterTo(mutableSetOf()) { id ->
            when (surface) {
                UiSurface.LIBRARY -> id !in source.catalog || workspaceSlotFor(id) in workspace
                UiSurface.MINI_PLAYER -> "workspace:mini_player" in workspace
                UiSurface.BOTTOM_NAVIGATION -> "workspace:navigation" in workspace
                else -> true
            }
        }
        val clicks = source.clicks + globalClicks
        val stock = clicks.map { component ->
            stockGesture(component, UiBuiltInGesture.TAP, UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = component))
        } + known.map { component ->
            stockGesture(component, UiBuiltInGesture.LONG_PRESS, UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = component))
        } + if (surface == UiSurface.NOW_PLAYING) listOf(previousCoverGesture, nextCoverGesture, queueCoverGesture) else emptyList()
        val environment = UiGestureEnvironment(visible, layout?.groups.orEmpty(), stock)
        if (profile.unbound.any { !known.containsAll(it.target.members) }) return false
        if (profile.bindings.any { binding ->
                val target = binding.action.targetId
                !environment.reaches(binding.slot.target) ||
                    binding.timelines.any { !environment.reaches(it.target) } ||
                    target != null && (target !in known || binding.action.id == UiGestureActionId.STOCK_CLICK && target !in clicks)
            }
        ) return false
        if (canvases.gestures.validate(surface, environment) != null) return false
    }
    return true
}

private fun effectiveComponents(native: Set<String>, layout: UiCanvasLayout?): Set<String> {
    if (layout == null) return native
    val placed = layout.items.mapTo(mutableSetOf(), UiCanvasItem::id)
    val hidden = layout.hiddenStock
    return if (hidden == null) placed else (native - hidden) + placed
}
