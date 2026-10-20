package io.github.lorddetson.piplayer.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StartFirstShuffleOrderTest {

    /** The songs in the order they play: from the first, along the next indices. */
    private fun ShuffleOrder.walk(): List<Int> =
        generateSequence(firstIndex.takeIf { it != C.INDEX_UNSET }) { getNextIndex(it).takeIf { next -> next != C.INDEX_UNSET } }
            .take(length + 1)
            .toList()

    @Test
    fun `a new queue plays its start song first and every song once`() {
        val order = StartFirstShuffleOrder(seed = 1).cloneAndSet(20_000, 9_999)

        val walk = order.walk()

        assertThat(order.length).isEqualTo(20_000)
        assertThat(walk.first()).isEqualTo(9_999)
        assertThat(walk).hasSize(20_000).doesNotHaveDuplicates()
        assertThat(order.lastIndex).isEqualTo(walk.last())
        assertThat(order.getNextIndex(walk.last())).isEqualTo(C.INDEX_UNSET)
    }

    @Test
    fun `previous goes back along the same order`() {
        val order = StartFirstShuffleOrder(seed = 2).cloneAndSet(1_000, 10)
        val walk = order.walk()

        assertThat(order.getPreviousIndex(walk.first())).isEqualTo(C.INDEX_UNSET)
        walk.zipWithNext().forEach { (song, next) -> assertThat(order.getPreviousIndex(next)).isEqualTo(song) }
    }

    @Test
    fun `the songs after the first are in random order`() {
        val walk = StartFirstShuffleOrder(seed = 3).cloneAndSet(1_000, 0).walk()
        val again = StartFirstShuffleOrder(seed = 3).cloneAndSet(1_000, 0).walk()
        val other = StartFirstShuffleOrder(seed = 4).cloneAndSet(1_000, 0).walk()

        assertThat(walk.drop(1)).isNotEqualTo((1 until 1_000).toList())
        assertThat(again).isEqualTo(walk)
        assertThat(other).isNotEqualTo(walk)
        assertThat(other.first()).isEqualTo(0)
    }

    @Test
    fun `every order of the other songs can come up`() {
        val orders = (1L..200L).map { seed -> StartFirstShuffleOrder(seed).cloneAndSet(3, 1).walk() }.toSet()

        assertThat(orders).containsExactlyInAnyOrder(listOf(1, 0, 2), listOf(1, 2, 0))
    }

    @Test
    fun `without a start song inside the queue the whole queue is shuffled`() {
        val unset = StartFirstShuffleOrder(seed = 5).cloneAndSet(10, C.INDEX_UNSET)
        val outside = StartFirstShuffleOrder(seed = 5).cloneAndSet(10, 10)
        val empty = StartFirstShuffleOrder(seed = 5).cloneAndSet(0, C.INDEX_UNSET)
        val firsts = (1L..200L).map { seed -> StartFirstShuffleOrder(seed).cloneAndSet(3, C.INDEX_UNSET).firstIndex }

        assertThat(unset.walk()).containsExactlyInAnyOrderElementsOf(0 until 10)
        assertThat(outside.walk()).containsExactlyInAnyOrderElementsOf(0 until 10)
        assertThat(empty.length).isZero()
        assertThat(empty.firstIndex).isEqualTo(C.INDEX_UNSET)
        assertThat(empty.lastIndex).isEqualTo(C.INDEX_UNSET)
        assertThat(firsts.toSet()).containsExactlyInAnyOrder(0, 1, 2)
    }

    @Test
    fun `shuffle turned on in the middle of a queue starts at the current song`() {
        val order = StartFirstShuffleOrder(seed = 6).startingAt(500, 123)

        assertThat(order.firstIndex).isEqualTo(123)
        assertThat(order.walk()).containsExactlyInAnyOrderElementsOf(0 until 500)
    }

    @Test
    fun `inserted songs come after the first song and the others keep their order`() {
        val base = StartFirstShuffleOrder(seed = 7).cloneAndSet(10, 4)

        val inserted = base.cloneAndInsert(2, 3)

        val walk = inserted.walk()
        assertThat(walk).containsExactlyInAnyOrderElementsOf(0 until 13)
        assertThat(walk.first()).isEqualTo(7)
        assertThat(walk.filter { it !in 2 until 5 }).isEqualTo(base.walk().map { if (it >= 2) it + 3 else it })
        assertThat(StartFirstShuffleOrder(seed = 7).cloneAndInsert(0, 5).walk()).containsExactlyInAnyOrderElementsOf(0 until 5)
    }

    @Test
    fun `removed songs leave the others in their order`() {
        val base = StartFirstShuffleOrder(seed = 8).cloneAndSet(10, 4)

        val removed = base.cloneAndRemove(1, 3)

        assertThat(removed.length).isEqualTo(8)
        assertThat(removed.walk()).isEqualTo(base.walk().filter { it !in 1 until 3 }.map { if (it >= 3) it - 2 else it })
        assertThat(removed.firstIndex).isEqualTo(2)
    }

    @Test
    fun `moved songs keep their place in the order`() {
        val base = StartFirstShuffleOrder(seed = 9).cloneAndSet(6, 0)

        // [0, 1, 2, 3, 4, 5] with songs 1 and 2 moved to start at 3 becomes [0, 3, 4, 1, 2, 5].
        val moved = base.cloneAndMove(1, 3, 3)

        val newIndex = mapOf(0 to 0, 1 to 3, 2 to 4, 3 to 1, 4 to 2, 5 to 5)
        assertThat(moved.walk()).isEqualTo(base.walk().map { newIndex.getValue(it) })
    }

    @Test
    fun `clearing empties the order`() {
        val cleared = StartFirstShuffleOrder(seed = 10).cloneAndSet(10, 3).cloneAndClear()

        assertThat(cleared.length).isZero()
        assertThat(cleared.firstIndex).isEqualTo(C.INDEX_UNSET)
    }

    @Test
    fun `20 000 songs are ordered within a few milliseconds`() {
        val order = StartFirstShuffleOrder(seed = 11)
        repeat(5) { order.cloneAndSet(20_000, it) }

        val times = List(9) { run ->
            val start = System.nanoTime()
            order.startingAt(20_000, run)
            (System.nanoTime() - start) / 1_000_000
        }

        assertThat(times.sorted()[times.size / 2]).isLessThanOrEqualTo(20L)
    }
}
