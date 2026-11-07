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

    @Test
    fun `an order given whole plays as given and tells its indices`() {
        val order = StartFirstShuffleOrder.of(intArrayOf(3, 0, 4, 1, 2), seed = 1)

        assertThat(order.walk()).containsExactly(3, 0, 4, 1, 2)
        assertThat(order.indices()).containsExactly(3, 0, 4, 1, 2)
        assertThat(StartFirstShuffleOrder(seed = 1).cloneAndSet(4, 2).let { (it as StartFirstShuffleOrder).indices() })
            .startsWith(2).hasSize(4)
    }

    @Test
    fun `a waiting order is taken by the next queue of its length, once`() {
        val waiting = StartFirstShuffleOrder(seed = 4).waitingFor(intArrayOf(2, 0, 1))

        val restored = waiting.cloneAndSet(3, 1)
        assertThat(restored.walk()).containsExactly(2, 0, 1)
        assertThat(restored.cloneAndClear().cloneAndSet(3, 1).walk().first()).isEqualTo(1)
    }

    @Test
    fun `a waiting order of another length gives way to a new shuffle`() {
        val waiting = StartFirstShuffleOrder(seed = 4).waitingFor(intArrayOf(2, 0, 1))

        assertThat(waiting.cloneAndSet(5, 4).walk()).startsWith(4).hasSize(5).doesNotHaveDuplicates()
    }

    @Test
    fun `an order that is not a permutation is refused`() {
        org.assertj.core.api.Assertions.assertThatThrownBy { StartFirstShuffleOrder.of(intArrayOf(0, 0, 1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `songs added after the queue advanced never land behind the current song`() {
        repeat(200) { seed ->
            val base = StartFirstShuffleOrder(seed.toLong()).cloneAndSet(10, 4) as StartFirstShuffleOrder
            // The sixth song of the order plays: the five before it are played.
            val played = base.walk().take(5)
            base.placeNextInsert(InsertPlacement.RandomAfter(5))

            val inserted = base.cloneAndInsert(10, 3)

            val walk = inserted.walk()
            assertThat(walk).containsExactlyInAnyOrderElementsOf(0 until 13)
            assertThat(walk.take(6)).isEqualTo(base.walk().take(6))
            assertThat(walk.indexOf(10)).isGreaterThan(5)
            assertThat(walk.indexOf(11)).isGreaterThan(5)
            assertThat(walk.indexOf(12)).isGreaterThan(5)
            assertThat(walk.take(5)).isEqualTo(played)
        }
    }

    @Test
    fun `songs added at a place of the order come there together in their order`() {
        val base = StartFirstShuffleOrder(seed = 12).cloneAndSet(8, 2) as StartFirstShuffleOrder
        base.placeNextInsert(InsertPlacement.At(3))

        // Two songs inserted at list index 3 shift the songs from 3 on by two.
        val inserted = base.cloneAndInsert(3, 2)

        val shifted = base.walk().map { if (it >= 3) it + 2 else it }
        assertThat(inserted.walk()).isEqualTo(shifted.take(3) + listOf(3, 4) + shifted.drop(3))
    }

    @Test
    fun `a placement is used by one insert only`() {
        val second = (1L..50L).map { seed ->
            val order = StartFirstShuffleOrder(seed).cloneAndSet(6, 0) as StartFirstShuffleOrder
            order.placeNextInsert(InsertPlacement.At(1))
            assertThat(order.cloneAndInsert(6, 1).walk().indexOf(6)).isEqualTo(1)
            order.cloneAndInsert(6, 1).walk().indexOf(6)
        }
        // Without a placement the same insert again goes anywhere after the first song.
        assertThat(second.toSet().size).isGreaterThan(1)
        assertThat(second).allMatch { it >= 1 }
    }

    @Test
    fun `shuffle turned on keeps the up-next run right after the current song`() {
        repeat(50) { seed ->
            val order = StartFirstShuffleOrder(seed.toLong()).startingAt(100, 40, intArrayOf(41, 42, 43))

            val walk = order.walk()
            assertThat(walk.take(4)).containsExactly(40, 41, 42, 43)
            assertThat(walk).containsExactlyInAnyOrderElementsOf(0 until 100)
        }
        assertThat(StartFirstShuffleOrder(seed = 1).startingAt(100, 40, intArrayOf(41)).walk().drop(2))
            .isNotEqualTo((0 until 100).filter { it != 40 && it != 41 })
    }

    @Test
    fun `a song moved in the play order plays at its new place and the others keep theirs`() {
        val order = StartFirstShuffleOrder.of(intArrayOf(3, 0, 4, 1, 2), seed = 1)

        assertThat(order.positionOf(4)).isEqualTo(2)
        assertThat(order.moved(from = 4, to = 1).walk()).containsExactly(3, 2, 0, 4, 1)
        assertThat(order.moved(from = 1, to = 3).walk()).containsExactly(3, 4, 1, 0, 2)
        assertThat(order.moved(from = 2, to = 2).walk()).containsExactly(3, 0, 4, 1, 2)
    }
}
