package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.playback.EntryKey.Opened
import io.github.lorddetson.piplayer.playback.EntryKey.Song
import java.util.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The saved shuffle order restored over the restored queue (docs/playback.md §4.8). */
class QueueOrderTest {

    @Test
    fun keysFollowThePlayOrder() {
        val entries = listOf(Song(10), Song(20), Opened(0), Song(30))

        assertThat(QueueOrder.keys(entries, intArrayOf(2, 0, 3, 1)))
            .containsExactly(Opened(0), Song(10), Song(30), Song(20))
    }

    @Test
    fun anUnchangedQueueGetsExactlyItsOrderPlayedSongsIncluded() {
        val entries = listOf(Song(10), Song(20), Song(30), Song(40), Song(50))
        val saved = listOf(Song(40), Song(10), Song(50), Song(20), Song(30))

        // The third in the order, song 50 at index 4, is playing: songs 40 and 10 are played.
        assertThat(QueueOrder.restore(saved, entries, current = 4, Random(1))).containsExactly(3, 0, 4, 1, 2)
    }

    @Test
    fun goneEntriesLeaveAndNewOnesComeOnlyAfterTheCurrent() {
        repeat(50) { seed ->
            val entries = listOf(Song(5), Song(10), Song(30), Song(40), Song(45), Song(50))
            val saved = listOf(Song(40), Song(10), Song(20), Song(50), Song(30))

            val order = QueueOrder.restore(saved, entries, current = 5, Random(seed.toLong()))!!
            val keys = order.map { entries[it] }

            assertThat(order.sorted()).containsExactly(0, 1, 2, 3, 4, 5)
            assertThat(keys.take(3)).containsExactly(Song(40), Song(10), Song(50))
            assertThat(keys.drop(3)).containsExactlyInAnyOrder(Song(5), Song(30), Song(45))
            assertThat(keys.indexOf(Song(30))).isGreaterThan(keys.indexOf(Song(50)))
        }
    }

    @Test
    fun newEntriesLandInDifferentPlaces() {
        val entries = (1L..20L).map { Song(it) }
        val saved = listOf(Song(1), Song(2))

        val orders = (0L until 20L).map { QueueOrder.restore(saved, entries, current = 0, Random(it))!!.toList() }.toSet()

        assertThat(orders.size).isGreaterThan(1)
    }

    @Test
    fun aSongTwiceInTheQueueKeepsBothPlaces() {
        val entries = listOf(Song(1), Song(2), Song(1))
        val saved = listOf(Song(1), Song(2), Song(1))

        assertThat(QueueOrder.restore(saved, entries, current = 1, Random(3))).containsExactly(0, 1, 2)
    }

    @Test
    fun aCurrentEntryThatTheSavedOrderLacksAsksForANewOrder() {
        val entries = listOf(Song(1), Song(2), Song(3))

        assertThat(QueueOrder.restore(listOf(Song(1), Song(2)), entries, current = 2, Random(1))).isNull()
        assertThat(QueueOrder.restore(emptyList(), entries, current = 0, Random(1))).isNull()
    }

    @Test
    fun newEntriesComeAfterTheUpNextRun() {
        repeat(50) { seed ->
            val entries = listOf(Song(10), EntryKey.Added(1), EntryKey.Added(2), Song(20), Song(30), Song(40), Song(50))
            val saved = listOf(Song(30), Song(10), EntryKey.Added(1), EntryKey.Added(2), Song(20))

            // Song 10 plays with the two added entries next; songs 40 and 50 are new.
            val order = QueueOrder.restore(
                saved, entries, current = 0, Random(seed.toLong()), upNext = setOf(EntryKey.Added(1), EntryKey.Added(2)),
            )!!
            val keys = order.map { entries[it] }

            assertThat(keys.take(4)).containsExactly(Song(30), Song(10), EntryKey.Added(1), EntryKey.Added(2))
            assertThat(keys.drop(4)).containsExactlyInAnyOrder(Song(20), Song(40), Song(50))
        }
    }

    @Test
    fun theLaterCopyOfASongTwiceInTheQueueStaysCurrentAtItsSavedPlace() {
        // A playlist of A, B, C, A played in the order rows 3, 1, 0, 2, with the second A (row 3) playing first.
        val entries = listOf(Song(1), Song(2), Song(3), Song(1))
        val saved = listOf(Song(1), Song(2), Song(1), Song(3))

        assertThat(QueueOrder.restore(saved, entries, current = 3, Random(1), currentPosition = 0)).containsExactly(3, 1, 0, 2)
    }

    @Test
    fun theEarlierCopyOfASongTwiceInTheQueueStaysCurrentAtItsSavedPlace() {
        // The same order with the first A (row 0) playing third: the second A and B are played.
        val entries = listOf(Song(1), Song(2), Song(3), Song(1))
        val saved = listOf(Song(1), Song(2), Song(1), Song(3))

        assertThat(QueueOrder.restore(saved, entries, current = 0, Random(1), currentPosition = 2)).containsExactly(3, 1, 0, 2)
    }

    @Test
    fun aSavedPlaceOfAnotherSongLeavesTheCopiesInTheirOrder() {
        // The saved place names another song than the current one (the library changed): it is not trusted.
        val entries = listOf(Song(1), Song(2), Song(1))
        val saved = listOf(Song(1), Song(2), Song(1))

        assertThat(QueueOrder.restore(saved, entries, current = 2, Random(3), currentPosition = 1)).containsExactly(0, 1, 2)
    }
}
