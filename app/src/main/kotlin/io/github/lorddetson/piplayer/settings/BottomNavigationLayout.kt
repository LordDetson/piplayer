package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class BottomDestination(val id: String) {
    LIBRARY("library"),
    SEARCH("search"),
    FIX("fix"),
    ;

    companion object {
        val byId = entries.associateBy(BottomDestination::id)
    }
}

internal data class BottomNavigationLayout(
    val order: List<BottomDestination> = BottomDestination.entries,
    val hidden: Set<BottomDestination> = emptySet(),
) {
    val visible = order.filterNot(hidden::contains)

    fun move(item: BottomDestination, target: BottomDestination): BottomNavigationLayout {
        if (item == target || item !in visible || target !in visible) return this
        val destination = order.indexOf(target)
        val reordered = order.toMutableList()
        reordered.remove(item)
        reordered.add(destination, item)
        return copy(order = reordered)
    }

    fun hide(item: BottomDestination): BottomNavigationLayout =
        if (visible.size > 1 && item in visible) copy(hidden = hidden + item) else this
}

@Serializable
internal data class BottomNavigationDocument(val order: String, val hidden: String) {
    fun toLayout(): BottomNavigationLayout? {
        val storedOrder = order.destinations() ?: return null
        val storedHidden = hidden.destinations() ?: return null
        if (storedOrder.size != BottomDestination.entries.size || storedOrder.toSet().size != storedOrder.size ||
            storedHidden.toSet().size != storedHidden.size || storedHidden.size >= storedOrder.size) return null
        return BottomNavigationLayout(storedOrder, storedHidden.toSet())
    }

    private fun String.destinations(): List<BottomDestination>? =
        if (isEmpty()) emptyList() else split(',').map { BottomDestination.byId[it] ?: return null }

    companion object {
        fun from(layout: BottomNavigationLayout) = BottomNavigationDocument(
            layout.order.joinToString(",", transform = BottomDestination::id),
            layout.order.filter(layout.hidden::contains).joinToString(",", transform = BottomDestination::id),
        )
    }
}

internal val bottomNavigationLayouts = PlayerPanelStore(
    "bottom_navigation_", BottomNavigationLayout(),
    decode = { Json.decodeFromString<BottomNavigationDocument>(it).toLayout() },
    encode = { Json.encodeToString(BottomNavigationDocument.from(it)) },
    defaultForTheme = { builtInThemeLayout(it)?.navigation },
)
