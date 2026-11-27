package io.github.lorddetson.piplayer.settings.librarytabs

import io.github.lorddetson.piplayer.library.customtabs.isCustomTabId
import kotlinx.serialization.Serializable

internal enum class LibraryButton(val id: String) {
    SEARCH("search"),
    SORT("sort"),
    SETTINGS("settings"),
    ;

    companion object {
        val ids = entries.map(LibraryButton::id)
        val byId = entries.associateBy(LibraryButton::id)
    }
}

internal data class LibraryButtonLayout(val order: List<String>, val hidden: Set<String>) {
    val visible: List<String> = order.filterNot(hidden::contains)

    companion object {
        val DEFAULT = LibraryButtonLayout(LibraryButton.ids, emptySet())
    }
}

internal data class LibraryPanelLayout(
    val tabs: LibraryTabLayout = LibraryTabLayout.DEFAULT,
    val buttons: LibraryButtonLayout = LibraryButtonLayout.DEFAULT,
) {
    fun withoutTabs(remove: (String) -> Boolean): LibraryPanelLayout {
        if ((tabs.order + tabs.hidden).none(remove)) return this
        val order = (tabs.order.filterNot(remove) + BuiltInLibraryTab.ids).distinct()
        val hidden = tabs.hidden.filterTo(mutableSetOf()) { !remove(it) && it in order }
        if (order.all(hidden::contains)) hidden.remove(order.first { it in BuiltInLibraryTab.ids })
        return copy(tabs = LibraryTabLayout(order, hidden))
    }
}

/** Stable IDs form the portable representation. Hidden buttons retain a position in the full order. */
@Serializable
internal data class LibraryPanelDocument(
    val tabOrder: String,
    val hiddenTabs: String,
    val buttonOrder: String,
    val hiddenButtons: String,
) {
    fun toLayout(knownCustomIds: Collection<String> = emptyList()): LibraryPanelLayout? {
        val available = BuiltInLibraryTab.ids + knownCustomIds.filter(::isCustomTabId).distinct()
        val tabs = tabOrder.knownIds(available) ?: return null
        val hiddenTabs = hiddenTabs.knownIds(available) ?: return null
        val buttons = buttonOrder.knownIds(LibraryButton.ids) ?: return null
        val hiddenButtons = hiddenButtons.knownIds(LibraryButton.ids) ?: return null
        if (hiddenTabs.size == available.size) return null
        return LibraryPanelLayout(
            LibraryTabLayout.fromStored(tabs, hiddenTabs.toSet(), available),
            LibraryButtonLayout((buttons + LibraryButton.ids).distinct(), hiddenButtons.toSet()),
        )
    }

    fun referencesTab(id: String): Boolean = id in tabOrder.split(',') || id in hiddenTabs.split(',')

    private fun String.knownIds(known: List<String>): List<String>? {
        val ids = if (isEmpty()) emptyList() else split(',')
        return ids.takeIf { it.size == it.toSet().size && known.containsAll(it) }
    }

    companion object {
        fun from(layout: LibraryPanelLayout) = LibraryPanelDocument(
            layout.tabs.order.joinToString(","),
            layout.tabs.order.filter(layout.tabs.hidden::contains).joinToString(","),
            layout.buttons.order.joinToString(","),
            layout.buttons.order.filter(layout.buttons.hidden::contains).joinToString(","),
        )
    }
}
