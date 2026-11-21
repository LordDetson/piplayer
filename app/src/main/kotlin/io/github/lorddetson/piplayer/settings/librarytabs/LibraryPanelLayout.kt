package io.github.lorddetson.piplayer.settings.librarytabs

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
)

/** Stable IDs form the portable representation. Hidden buttons retain a position in the full order. */
@Serializable
internal data class LibraryPanelDocument(
    val tabOrder: String,
    val hiddenTabs: String,
    val buttonOrder: String,
    val hiddenButtons: String,
) {
    fun toLayout(): LibraryPanelLayout? {
        val tabs = tabOrder.knownIds(BuiltInLibraryTab.ids) ?: return null
        val hiddenTabs = hiddenTabs.knownIds(BuiltInLibraryTab.ids) ?: return null
        val buttons = buttonOrder.knownIds(LibraryButton.ids) ?: return null
        val hiddenButtons = hiddenButtons.knownIds(LibraryButton.ids) ?: return null
        if (hiddenTabs.size == BuiltInLibraryTab.ids.size) return null
        return LibraryPanelLayout(
            LibraryTabLayout.fromStored(tabs, hiddenTabs.toSet()),
            LibraryButtonLayout((buttons + LibraryButton.ids).distinct(), hiddenButtons.toSet()),
        )
    }

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
