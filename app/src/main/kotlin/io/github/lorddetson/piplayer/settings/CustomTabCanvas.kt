package io.github.lorddetson.piplayer.settings

import io.github.lorddetson.piplayer.library.customtabs.customTabIdForComponent
import io.github.lorddetson.piplayer.settings.librarytabs.BuiltInLibraryTab
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout

internal fun UiThemeCanvases.referencedCustomTabIds(): Set<String> = buildSet {
    fun record(id: String) { customTabIdForComponent(id)?.let(::add) }
    for (panel in panels.values) {
        panel.items.forEach { record(it.id) }
        panel.hiddenStock.orEmpty().forEach(::record)
        panel.groups.flatten().forEach(::record)
        panel.labelDisplays.keys.forEach(::record)
    }
    for (surface in gestures.surfaces.values) {
        surface.slots.flatMap { it.target.members }.forEach(::record)
        for (binding in surface.bindings) {
            binding.action.targetId?.let(::record)
            binding.timelines.flatMap { it.target.members }.forEach(::record)
        }
    }
}

internal data class TabCanvasProjection(val panel: LibraryPanelLayout, val canvases: UiThemeCanvases)

/** Removes tabs and their dependent assignments together. Each surviving gesture keeps its original target. */
internal fun UiThemeCanvases.withoutCustomTabs(
    panel: LibraryPanelLayout,
    remove: (String) -> Boolean,
): TabCanvasProjection {
    var library = panel.withoutTabs(remove)
    if (referencedCustomTabIds().none(remove)) return TabCanvasProjection(library, this)

    fun removeComponent(id: String): Boolean = customTabIdForComponent(id)?.let(remove) == true
    val layouts = panels.mapValues { (_, layout) ->
        layout.copy(
            items = layout.items.filterNot { removeComponent(it.id) },
            hiddenStock = layout.hiddenStock?.filterNot(::removeComponent)?.toSet(),
            groups = layout.groups.filterNot { group -> group.any(::removeComponent) },
            labelDisplays = layout.labelDisplays.filterKeys { !removeComponent(it) },
        )
    }.toMutableMap()
    val header = layouts[UiSurface.LIBRARY]
    val previous = panels[UiSurface.LIBRARY]
    if (header != null && previous != null && header.availableComponents(library).none { it.startsWith("tab:") } &&
        previous.availableComponents(panel).any(::removeComponent)
    ) {
        val fallback = library.tabs.order.first { it in BuiltInLibraryTab.ids }
        library = library.copy(tabs = library.tabs.copy(hidden = library.tabs.hidden - fallback))
        val fallbackId = "tab:$fallback"
        val hidden = header.hiddenStock
        layouts[UiSurface.LIBRARY] = if (hidden == null) {
            val index = previous.items.indexOfFirst { removeComponent(it.id) }
            val items = header.items.toMutableList().apply { add(index, UiCanvasItem(fallbackId, previous.items[index].bounds)) }
            header.copy(items = items)
        } else {
            header.copy(hiddenStock = hidden - fallbackId)
        }
    }
    var profile = gestures
    for ((surface, current) in gestures.surfaces) {
        val removed = current.bindings.filter { binding ->
            binding.slot.target.members.any(::removeComponent) || binding.action.targetId?.let(::removeComponent) == true
        }
        var pruned = current.copy(
            bindings = (current.bindings - removed.toSet()).map { binding ->
                binding.copy(timelines = binding.timelines.filterNot { it.target.members.any(::removeComponent) })
            },
            unbound = current.unbound.filterNot { it.target.members.any(::removeComponent) }.toSet(),
        )
        val layout = layouts[surface]
        val components = if (surface == UiSurface.LIBRARY) layout.availableComponents(library)
            else pruned.slots.flatMapTo(mutableSetOf()) { it.target.members }
        val lostActions = ArrayDeque(removed.map(UiGestureBinding::action).distinct())
        val restoredSlots = mutableSetOf<UiGestureSlot>()
        while (lostActions.isNotEmpty()) {
            val action = lostActions.removeFirst()
            val target = action.targetId?.takeUnless(::removeComponent) ?: continue
            val defaultGesture = when (action.id) {
                UiGestureActionId.STOCK_CLICK -> UiBuiltInGesture.TAP
                UiGestureActionId.CONTEXT_MENU -> UiBuiltInGesture.LONG_PRESS
                else -> continue
            }
            val slot = UiGestureSlot(UiGestureTarget.component(target), UiGesture.builtIn(defaultGesture))
            if (!restoredSlots.add(slot)) continue
            val defaultAction = UiGestureAction(action.id, targetId = target)
            val stock = UiGestureBinding(slot, defaultAction)
            val environment = UiGestureEnvironment(components + target, layout?.groups.orEmpty(), listOf(stock))
            fun reachesStock(gestures: UiSurfaceGestures): Boolean =
                profile.editSurface(surface, gestures).resolve(surface, environment).any { it.action == defaultAction }
            if (reachesStock(pruned)) continue
            pruned.bindings.firstOrNull { it.slot == slot }?.action?.let(lostActions::addLast)
            pruned = pruned.restore(slot)
            if (!reachesStock(pruned)) pruned = pruned.assign(stock)
        }
        profile = profile.editSurface(surface, pruned)
    }
    return TabCanvasProjection(library, copy(panels = layouts, gestures = profile))
}

private fun UiCanvasLayout?.availableComponents(panel: LibraryPanelLayout): Set<String> {
    if (this == null) return panel.components()
    val placed = items.mapTo(mutableSetOf(), UiCanvasItem::id)
    val hidden = hiddenStock ?: return placed
    return (panel.components() - hidden) + placed
}

private fun LibraryPanelLayout.components(): Set<String> =
    (tabs.visible.map { "tab:$it" } + buttons.visible.map { "library:$it" } + "library:title").toSet()
