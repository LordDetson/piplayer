package io.github.lorddetson.piplayer.playback

/**
 * Which queue items the session compares after a change of decided values (TI §7.1, docs/playback.md §2.6): the playing
 * item, then the items of the touched files in play order after it, wrapping round to those before it, at most
 * [MAX_QUEUED] of them. An item of another file, the next one too, is left as it is.
 */
internal object DecidedRefresh {

    /** The most queued items one change compares besides the playing one; the others catch up at their transition. */
    const val MAX_QUEUED = 20

    /**
     * The indices to compare in a queue of [count] items playing [current]: [next] gives the item after an index in play
     * order, wrapping, and [fileOf] an item's file, null for a file opened from another app.
     */
    fun indices(current: Int, count: Int, next: (Int) -> Int, fileOf: (Int) -> Long?, touched: Set<Long>, max: Int = MAX_QUEUED): List<Int> {
        if (current !in 0 until count) return emptyList()
        val chosen = arrayListOf(current)
        if (touched.isEmpty()) return chosen
        val seen = HashSet<Int>().apply { add(current) }
        var index = current
        // At most once round the queue, also for an order that never comes back to the playing item.
        repeat(count - 1) {
            index = next(index)
            if (index !in 0 until count || !seen.add(index)) return chosen
            val file = fileOf(index)
            if (file != null && file in touched) {
                chosen += index
                if (chosen.size > max) return chosen
            }
        }
        return chosen
    }
}
