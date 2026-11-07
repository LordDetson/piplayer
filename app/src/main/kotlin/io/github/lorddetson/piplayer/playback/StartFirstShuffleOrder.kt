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
    /** An order the next queue of its length takes as it is: a restored one (docs/playback.md §4.8). */
    private val waiting: IntArray? = null,
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
    fun startingAt(length: Int, first: Int): StartFirstShuffleOrder = startingAt(length, first, IntArray(0))

    /**
     * A new order of [length] songs with [first] first, then the songs of [next] in their order (the up-next run,
     * docs/playback.md §9.3), and the others shuffled after them.
     */
    fun startingAt(length: Int, first: Int, next: IntArray): StartFirstShuffleOrder {
        val order = IntArray(length) { it }
        val placed = BooleanArray(length)
        var from = 0
        // The first song and the run go to the front in turn, each swapped with what stood there.
        val front = if (first in 0 until length) intArrayOf(first) + next.filter { it in 0 until length && it != first } else IntArray(0)
        val where = IntArray(length) { it }
        for (song in front) {
            if (placed[song]) continue
            placed[song] = true
            val at = where[song]
            val other = order[from]
            order[from] = song
            order[at] = other
            where[other] = at
            where[song] = from
            from++
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

    /** Where the song at [index] stands in the play order. */
    fun positionOf(index: Int): Int = positions[index]

    /** The same songs with the one at play [from] moved to play [to] (a drag while shuffle is on, §9.2). */
    fun moved(from: Int, to: Int): StartFirstShuffleOrder {
        val order = shuffled.toMutableList()
        order.add(to, order.removeAt(from))
        return StartFirstShuffleOrder(order.toIntArray(), child())
    }

    /**
     * Where the songs of the next [cloneAndInsert] go in the play order (docs/playback.md §9.3). The player inserts into
     * the order it holds, so this tells that very order; it is used once.
     */
    fun placeNextInsert(placement: InsertPlacement) {
        nextInsert = placement
    }

    /** The placement of the next insert; the one mutable part of the order, read once. */
    @Volatile
    private var nextInsert: InsertPlacement? = null

    override fun getLength(): Int = shuffled.size

    override fun getNextIndex(index: Int): Int =
        positions[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else C.INDEX_UNSET }

    override fun getPreviousIndex(index: Int): Int = positions[index].let { if (it > 0) shuffled[it - 1] else C.INDEX_UNSET }

    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.last()

    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled[0]

    /** A new queue: its start song first. */
    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
        if (waiting != null && waiting.size == insertionCount) {
            StartFirstShuffleOrder(waiting, child())
        } else {
            startingAt(insertionCount, startIndex)
        }

    /**
     * Songs added at [insertionIndex] go where [placeNextInsert] said: together at a place of the order, or at random
     * places after one. Without a placement they go to random places, but never before its first song, which is playing
     * when nothing else is known. The songs already there keep their order.
     */
    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val placement = nextInsert
        nextInsert = null
        val random = child()
        val values = IntArray(insertionCount) { insertionIndex + it }
        // Each new song goes before the old song at its point, or after all of them at the order's length.
        val points = when (placement) {
            is InsertPlacement.At -> IntArray(insertionCount) { placement.position.coerceIn(0, shuffled.size) }
            else -> {
                val after = when (placement) {
                    is InsertPlacement.RandomAfter -> (placement.position + 1).coerceIn(0, shuffled.size)
                    else -> if (shuffled.isEmpty()) 0 else 1
                }
                for (i in insertionCount - 1 downTo 1) {
                    val j = random.nextInt(i + 1)
                    val song = values[i]
                    values[i] = values[j]
                    values[j] = song
                }
                IntArray(insertionCount) { after + random.nextInt(shuffled.size - after + 1) }.apply { sort() }
            }
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

    /** The songs' indices in the order they play. */
    fun indices(): IntArray = shuffled.copyOf()

    /** An empty order that hands [order] to the next queue of its length (a restore), and shuffles anew otherwise. */
    fun waitingFor(order: IntArray): StartFirstShuffleOrder = StartFirstShuffleOrder(IntArray(0), child(), of(order).shuffled)

    companion object {
        /** The order [order], which must hold every index below its size once. */
        fun of(order: IntArray, seed: Long = Random().nextLong()): StartFirstShuffleOrder {
            val seen = BooleanArray(order.size)
            for (index in order) {
                require(index in order.indices && !seen[index]) { "Not an order of ${order.size} songs" }
                seen[index] = true
            }
            return StartFirstShuffleOrder(order.copyOf(), Random(seed))
        }
    }
}

/** Where inserted songs go in the play order (docs/playback.md §9.3). */
sealed interface InsertPlacement {
    /** Together, in their order, starting at play [position]. */
    data class At(val position: Int) : InsertPlacement

    /** Each at a random place after play [position]. */
    data class RandomAfter(val position: Int) : InsertPlacement
}
