package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class MiniPlayerElement(val id: String) {
    COVER("cover"),
    TRACK_INFO("track_info"),
    PLAY_PAUSE("play_pause"),
    PROGRESS("progress"),
    ;

    companion object {
        val byId = entries.associateBy(MiniPlayerElement::id)
        val row = entries.filterNot { it == PROGRESS }
    }
}

internal data class MiniPlayerPanel(
    val order: List<MiniPlayerElement> = MiniPlayerElement.row,
    val hidden: Set<MiniPlayerElement> = emptySet(),
    val progressAtBottom: Boolean = false,
) {
    val visible: List<MiniPlayerElement> = order.filterNot(hidden::contains)

    fun move(element: MiniPlayerElement, target: MiniPlayerElement): MiniPlayerPanel {
        if (element == target || element !in visible || target !in visible) return this
        val updated = order.toMutableList()
        val destination = updated.indexOf(target)
        updated.remove(element)
        updated.add(destination, element)
        return copy(order = updated)
    }
}

@Serializable
internal data class MiniPlayerPanelDocument(val order: String, val hidden: String, val progress: String) {
    fun toPanel(): MiniPlayerPanel? {
        val storedOrder = order.elements() ?: return null
        val storedHidden = hidden.elements() ?: return null
        if (storedOrder.size != MiniPlayerElement.row.size || storedOrder.toSet() != MiniPlayerElement.row.toSet() ||
            storedHidden.toSet().size != storedHidden.size || progress !in setOf("top", "bottom")) return null
        return MiniPlayerPanel(storedOrder, storedHidden.toSet(), progressAtBottom = progress == "bottom")
    }

    private fun String.elements(): List<MiniPlayerElement>? =
        if (isEmpty()) emptyList() else split(',').map { MiniPlayerElement.byId[it] ?: return null }

    companion object {
        fun from(panel: MiniPlayerPanel) = MiniPlayerPanelDocument(
            panel.order.joinToString(",", transform = MiniPlayerElement::id),
            MiniPlayerElement.entries.filter(panel.hidden::contains).joinToString(",", transform = MiniPlayerElement::id),
            if (panel.progressAtBottom) "bottom" else "top",
        )
    }
}

internal val miniPlayerPanels = PlayerPanelStore(
    "mini_player_panel_", MiniPlayerPanel(),
    decode = { Json.decodeFromString<MiniPlayerPanelDocument>(it).toPanel() },
    encode = { Json.encodeToString(MiniPlayerPanelDocument.from(it)) },
    defaultForTheme = { builtInThemeLayout(it)?.miniPlayer },
)
