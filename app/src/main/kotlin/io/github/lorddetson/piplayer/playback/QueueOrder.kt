package io.github.lorddetson.piplayer.playback

import java.util.Random

/** The saved shuffle order and the restored queue (docs/playback.md §4.8). */
object QueueOrder {

    /** The keys of [entries] in the play order [order], which holds their indices. */
    fun keys(entries: List<EntryKey>, order: IntArray): List<EntryKey> = order.map { entries[it] }

    /**
     * The play order of the restored [entries], as indices: the saved [order] for the entries that are still there, and
     * new entries at random places after [current]; null when the current entry is not in the saved order, so that the
     * caller generates a new one.
     */
    fun restore(saved: List<EntryKey>, entries: List<EntryKey>, current: Int, random: Random): IntArray? {
        // Each key may stand more than once in a queue; its places are taken in order.
        val places = HashMap<EntryKey, ArrayDeque<Int>>()
        entries.forEachIndexed { index, key -> places.getOrPut(key) { ArrayDeque() }.addLast(index) }
        val order = ArrayList<Int>(entries.size)
        val used = BooleanArray(entries.size)
        for (key in saved) {
            val index = places[key]?.removeFirstOrNull() ?: continue
            order += index
            used[index] = true
        }
        val currentPosition = order.indexOf(current)
        if (currentPosition < 0) return null
        val fresh = entries.indices.filterNot { used[it] }.shuffled(random)
        if (fresh.isEmpty()) return order.toIntArray()
        // The new entries take random places among those after the current one, the old ones keeping their order.
        val tail = order.subList(currentPosition + 1, order.size).toList()
        val slots = BooleanArray(tail.size + fresh.size)
        var placed = 0
        while (placed < fresh.size) {
            val slot = random.nextInt(slots.size)
            if (!slots[slot]) {
                slots[slot] = true
                placed++
            }
        }
        val result = IntArray(entries.size)
        for (i in 0..currentPosition) result[i] = order[i]
        var old = 0
        var new = 0
        for (slot in slots.indices) result[currentPosition + 1 + slot] = if (slots[slot]) fresh[new++] else tail[old++]
        return result
    }
}
