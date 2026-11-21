package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class NowPlayingRow { TOP, CONTENT, CONTROLS, EXTRAS }

internal enum class NowPlayingElement(val id: String, val initialRow: NowPlayingRow, val primary: Boolean = false) {
    IDENTIFY("identify", NowPlayingRow.TOP),
    FAVORITE("favorite", NowPlayingRow.TOP),
    QUEUE("queue", NowPlayingRow.TOP),
    MORE("more", NowPlayingRow.TOP),
    COVER("cover", NowPlayingRow.CONTENT, primary = true),
    TRACK_INFO("track_info", NowPlayingRow.CONTENT, primary = true),
    FORMAT("format", NowPlayingRow.CONTENT),
    SEEK("seek", NowPlayingRow.CONTENT, primary = true),
    SHUFFLE("shuffle", NowPlayingRow.CONTROLS),
    PREVIOUS("previous", NowPlayingRow.CONTROLS, primary = true),
    PLAY_PAUSE("play_pause", NowPlayingRow.CONTROLS, primary = true),
    NEXT("next", NowPlayingRow.CONTROLS, primary = true),
    REPEAT("repeat", NowPlayingRow.CONTROLS),
    TIMER("timer", NowPlayingRow.EXTRAS),
    STOP_AFTER("stop_after", NowPlayingRow.EXTRAS),
    EQUALIZER("equalizer", NowPlayingRow.EXTRAS),
    ;

    fun fits(row: NowPlayingRow): Boolean = (row == NowPlayingRow.CONTENT) == (initialRow == NowPlayingRow.CONTENT)

    companion object {
        val byId = entries.associateBy(NowPlayingElement::id)
    }
}

internal data class NowPlayingPanel(
    val rows: Map<NowPlayingRow, List<NowPlayingElement>>,
    val hidden: Set<NowPlayingElement> = emptySet(),
) {
    operator fun get(row: NowPlayingRow): List<NowPlayingElement> = rows.getValue(row)

    fun rowOf(element: NowPlayingElement): NowPlayingRow = rows.entries.first { element in it.value }.key

    fun move(element: NowPlayingElement, target: NowPlayingElement): NowPlayingPanel {
        val source = rowOf(element)
        val destination = rowOf(target)
        if (element == target || !element.fits(destination) || target in hidden || element in hidden) return this
        val after = source < destination || source == destination && this[source].indexOf(element) < this[source].indexOf(target)
        val targetList = (this[destination] - element).toMutableList()
        targetList.add(targetList.indexOf(target) + if (after) 1 else 0, element)
        return copy(rows = rows + (source to (this[source] - element)) + (destination to targetList))
    }

    fun moveToEnd(element: NowPlayingElement, destination: NowPlayingRow): NowPlayingPanel {
        val source = rowOf(element)
        if (source == destination || !element.fits(destination) || element in hidden) return this
        return copy(rows = rows + (source to (this[source] - element)) + (destination to (this[destination] + element)))
    }

    companion object {
        val DEFAULT = NowPlayingPanel(NowPlayingRow.entries.associateWith { row ->
            NowPlayingElement.entries.filter { it.initialRow == row }
        })
    }
}

@Serializable
internal data class NowPlayingPanelDocument(
    val top: String,
    val content: String,
    val controls: String,
    val extras: String,
    val hidden: String,
) {
    fun toPanel(): NowPlayingPanel? {
        val rows = mapOf(
            NowPlayingRow.TOP to (top.elements() ?: return null),
            NowPlayingRow.CONTENT to (content.elements() ?: return null),
            NowPlayingRow.CONTROLS to (controls.elements() ?: return null),
            NowPlayingRow.EXTRAS to (extras.elements() ?: return null),
        )
        val all = rows.values.flatten()
        val hiddenItems = hidden.elements() ?: return null
        if (all.size != NowPlayingElement.entries.size || all.toSet().size != all.size ||
            rows.any { (row, items) -> items.any { !it.fits(row) } } || hiddenItems.toSet().size != hiddenItems.size) return null
        return NowPlayingPanel(rows, hiddenItems.toSet())
    }

    private fun String.elements(): List<NowPlayingElement>? =
        if (isEmpty()) emptyList() else split(',').map { NowPlayingElement.byId[it] ?: return null }

    companion object {
        fun from(panel: NowPlayingPanel) = NowPlayingPanelDocument(
            panel[NowPlayingRow.TOP].ids(),
            panel[NowPlayingRow.CONTENT].ids(),
            panel[NowPlayingRow.CONTROLS].ids(),
            panel[NowPlayingRow.EXTRAS].ids(),
            NowPlayingElement.entries.filter(panel.hidden::contains).ids(),
        )

        private fun List<NowPlayingElement>.ids(): String = joinToString(",", transform = NowPlayingElement::id)
    }
}

internal val nowPlayingPanels = PlayerPanelStore(
    "now_playing_panel_", NowPlayingPanel.DEFAULT,
    decode = { Json.decodeFromString<NowPlayingPanelDocument>(it).toPanel() },
    encode = { Json.encodeToString(NowPlayingPanelDocument.from(it)) },
    defaultForTheme = { builtInThemeLayout(it)?.player },
)
