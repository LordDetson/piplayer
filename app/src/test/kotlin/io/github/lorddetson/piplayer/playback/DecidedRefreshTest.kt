package io.github.lorddetson.piplayer.playback

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which queue items the session compares after a change of decided values (TI §7.1, docs/playback.md §2.6): the playing
 * item, then the items of the touched files in play order after it, at most 20 of them.
 */
class DecidedRefreshTest {

    /** A queue of [files] in list order (null: an opened file), played in [order] (list indices), wrapping. */
    private fun indices(files: List<Long?>, current: Int, touched: Set<Long>, order: List<Int> = files.indices.toList(), max: Int = DecidedRefresh.MAX_QUEUED): List<Int> {
        val position = IntArray(order.size).also { array -> order.forEachIndexed { at, index -> array[index] = at } }
        return DecidedRefresh.indices(
            current = current,
            count = files.size,
            next = { index -> order[(position[index] + 1) % order.size] },
            fileOf = { files[it] },
            touched = touched,
            max = max,
        )
    }

    @Test
    fun thePlayingItemComesFirstThenTheItemsOfTheTouchedFilesAndNoOther() {
        val queue = listOf(1L, 2L, 3L, 1L, 4L)

        assertThat(indices(queue, current = 0, touched = setOf(1L, 4L))).containsExactly(0, 3, 4)
    }

    @Test
    fun theNextItemOfAnotherFileIsLeftOut() {
        assertThat(indices(listOf(1L, 2L, 1L), current = 0, touched = setOf(1L))).containsExactly(0, 2)
    }

    @Test
    fun thePlayingItemIsComparedAlsoWhenTheChangeDidNotTouchIt() {
        assertThat(indices(listOf(1L, 2L, 3L), current = 1, touched = setOf(3L))).containsExactly(1, 2)
        assertThat(indices(listOf(1L, 2L, 3L), current = 1, touched = emptySet())).containsExactly(1)
    }

    @Test
    fun theItemsComeInPlayOrderWithThoseBeforeThePlayingItemLast() {
        val queue = listOf(5L, 5L, 5L, 5L, 5L)

        assertThat(indices(queue, current = 2, touched = setOf(5L))).containsExactly(2, 3, 4, 0, 1)
    }

    @Test
    fun aShuffledQueueGoesInItsPlayOrder() {
        val queue = listOf(5L, 6L, 5L, 5L, 7L, 5L)
        val order = listOf(4, 2, 0, 5, 1, 3)

        assertThat(indices(queue, current = 0, touched = setOf(5L), order = order)).containsExactly(0, 5, 3, 2)
    }

    @Test
    fun atMostTwentyItemsBesideThePlayingOne() {
        val queue = List(50) { 9L }

        val chosen = indices(queue, current = 10, touched = setOf(9L))

        assertThat(chosen).hasSize(1 + DecidedRefresh.MAX_QUEUED)
        assertThat(chosen).containsExactlyElementsOf(listOf(10) + (11..30))
    }

    @Test
    fun anOpenedFileIsNeverChosenBesideThePlayingItem() {
        assertThat(indices(listOf(1L, null, 1L), current = 0, touched = setOf(1L))).containsExactly(0, 2)
        assertThat(indices(listOf(null, 1L), current = 0, touched = setOf(1L))).containsExactly(0, 1)
    }

    @Test
    fun anEmptyQueueOrNoPlayingItemGivesNothing() {
        assertThat(indices(emptyList(), current = -1, touched = setOf(1L))).isEmpty()
        assertThat(indices(listOf(1L, 2L), current = -1, touched = setOf(1L))).isEmpty()
        assertThat(indices(listOf(1L, 2L), current = 2, touched = setOf(1L))).isEmpty()
    }

    @Test
    fun anOrderThatNeverComesBackStopsAfterTheQueue() {
        val chosen = DecidedRefresh.indices(current = 0, count = 3, next = { 1 }, fileOf = { 1L }, touched = setOf(1L), max = 20)

        assertThat(chosen).containsExactly(0, 1)
    }
}
