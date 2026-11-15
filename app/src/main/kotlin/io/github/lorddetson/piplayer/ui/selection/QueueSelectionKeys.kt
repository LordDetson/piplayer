package io.github.lorddetson.piplayer.ui.selection

/**
 * The queue screen's keys under a change of the queue (docs/playback.md §9.6): an entry is keyed by its media ID and, for
 * a media ID held more than once, by its copy number in the play order, which a change of the queue can give to another
 * entry. So the entries of such media IDs leave the selection; the others stay.
 */
object QueueSelectionKeys {

    /**
     * [selection] after the queue went from [before] to [after], each its entries' keys with their media IDs in the order
     * shown: unchanged when the keys are the same, otherwise without the entries whose media ID either queue holds more
     * than once. Null when nothing selected is left.
     */
    fun afterChange(
        selection: Selection<String>?,
        before: List<Pair<String, String>>,
        after: List<Pair<String, String>>,
    ): Selection<String>? {
        if (selection == null || before.map { it.first } == after.map { it.first }) return selection
        val counts = HashMap<String, Int>()
        val ambiguous = HashSet<String>()
        for (queue in listOf(before, after)) {
            counts.clear()
            for ((_, mediaId) in queue) if (counts.merge(mediaId, 1, Int::plus)!! > 1) ambiguous += mediaId
        }
        if (ambiguous.isEmpty()) return selection
        val mediaOf = HashMap<String, String>()
        for ((key, mediaId) in before + after) mediaOf[key] = mediaId
        return when (selection) {
            is Selection.Only -> selection.keys.filterTo(LinkedHashSet()) { mediaOf[it] !in ambiguous }
                .takeIf { it.isNotEmpty() }?.let { Selection.Only(it) }
            is Selection.AllExcept -> selection.copy(
                excluded = selection.excluded + after.filter { (_, mediaId) -> mediaId in ambiguous }.map { it.first },
            )
        }
    }
}
