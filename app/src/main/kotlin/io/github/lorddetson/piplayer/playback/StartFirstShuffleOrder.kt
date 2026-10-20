package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

/**
 * A shuffle order that plays one song first and every other song once after it, in random order
 * (docs/playback.md §3.6). The player passes the start song of each new queue to [cloneAndSet]; for shuffle turned on
 * in the middle of a queue, [SessionPlayer] sets [startingAt] the current song. Like Media3's own order it is
 * immutable, and each copy shuffles with a generator seeded from its parent's.
 */
@OptIn(UnstableApi::class)
class StartFirstShuffleOrder private constructor(
    /** The songs' indices in the order they play. */
    private val shuffled: IntArray,
    private val random: Random,
) : ShuffleOrder {

    /** Where each song stands in [shuffled]. */
    private val positions = IntArray(shuffled.size).also { positions ->
        shuffled.forEachIndexed { position, index -> positions[index] = position }
    }

    /** An empty order, the one a player starts with. */
    constructor(seed: Long = Random().nextLong()) : this(IntArray(0), Random(seed))

    /**
     * A new order of [length] songs with [first] first and the others shuffled after it; a [first] outside the queue
     * (such as [C.INDEX_UNSET]) shuffles them all.
     */
    fun startingAt(length: Int, first: Int): StartFirstShuffleOrder {
        val order = IntArray(length) { it }
        var from = 0
        if (first in 0 until length) {
            order[0] = first
            order[first] = 0
            from = 1
        }
        val random = child()
        for (i in length - 1 downTo from + 1) {
            val j = from + random.nextInt(i - from + 1)
            val song = order[i]
            order[i] = order[j]
            order[j] = song
        }
        return StartFirstShuffleOrder(order, random)
    }

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int =
        positions[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int = positions[index].let { if (it > 0) shuffled[it - 1] else C.INDEX_UNSET }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.last()

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[0]

    /** A new queue: its start song first. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder = startingAt(insertionCount, startIndex)

    /**
     * Songs added at [insertionIndex] go to random places of the order, but never before its first song, which is
     * playing; the songs already there keep their order.
     */
    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val random = child()
        val points = IntArray(insertionCount) { if (shuffled.isEmpty()) 0 else 1 + random.nextInt(shuffled.size) }.apply { sort() }
        val values = IntArray(insertionCount) { insertionIndex + it }
        for (i in insertionCount - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val song = values[i]
            values[i] = values[j]
            values[j] = song
        }
        val order = IntArray(shuffled.size + insertionCount)
        var old = 0
        var inserted = 0
        for (position in order.indices) {
            order[position] = if (inserted < insertionCount && points[inserted] == old) {
                values[inserted++]
            } else {
                shuffled[old++].let { if (it >= insertionIndex) it + insertionCount else it }
            }
        }
        return StartFirstShuffleOrder(order, random)
    }

    /** The songs from [indexFrom] to [indexToExclusive] leave; the others keep their order. */
    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val order = IntArray(shuffled.size - count)
        var position = 0
        for (index in shuffled) {
            if (index in indexFrom until indexToExclusive) continue
            order[position++] = if (index >= indexToExclusive) index - count else index
        }
        return StartFirstShuffleOrder(order, child())
    }

    /**
     * The songs from [indexFrom] to [indexToExclusive] move to start at [newIndexFrom]: each song keeps its place in
     * the order under its new index. Media3's default would keep the indices, and so play other songs.
     */
    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        val order = IntArray(shuffled.size) { position ->
            val index = shuffled[position]
            if (index in indexFrom until indexToExclusive) {
                newIndexFrom + (index - indexFrom)
            } else {
                val rest = if (index < indexFrom) index else index - count
                if (rest < newIndexFrom) rest else rest + count
            }
        }
        return StartFirstShuffleOrder(order, child())
    }

    override fun cloneAndClear(): ShuffleOrder = StartFirstShuffleOrder(IntArray(0), child())

    private fun child() = Random(random.nextLong())
}
