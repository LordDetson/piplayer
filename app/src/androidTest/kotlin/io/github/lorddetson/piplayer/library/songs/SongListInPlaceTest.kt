package io.github.lorddetson.piplayer.library.songs

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.library.db.SongRow
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The kept list while the tag pass changes rows in place (docs/library.md §1.4). */
@RunWith(AndroidJUnit4::class)
class SongListInPlaceTest {

    private fun song(id: Long, title: String) = Song(id, "uri$id", title, "Artist", "Album", 1_000)
    private fun row(id: Long, title: String) = SongRow(id, title, "New artist", "New album", 2_000)

    @Before
    @After
    fun forget() {
        SongList.forget()
    }

    @Test
    fun aBatchChangesTheKeptRowsWithoutMovingThem() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })

        SongList.applyInPlace(listOf(row(1, "Z")))

        val list = SongList.current()!!
        assertThat(list.map { it.id }).containsExactly(1L, 2L)
        assertThat(list[0].title).isEqualTo("Z")
        assertThat(list[0].artist).isEqualTo("New artist")
        assertThat(list[0].album).isEqualTo("New album")
        assertThat(list[0].durationMs).isEqualTo(2_000)
        assertThat(list[0].uri).isEqualTo("uri1")
        assertThat(list[1].title).isEqualTo("B")
    }

    @Test
    fun aRowTheListDoesNotHoldIsIgnored() {
        SongList.load(SongSource { listOf(song(1, "A")) })

        SongList.applyInPlace(listOf(row(9, "Z")))

        assertThat(SongList.current()!!.map { it.id }).containsExactly(1L)
    }

    @Test
    fun aBatchIsAnnounced() {
        SongList.load(SongSource { listOf(song(1, "A")) })
        val before = SongList.batches.value

        SongList.applyInPlace(listOf(row(1, "Z")))

        assertThat(SongList.batches.value).isGreaterThan(before)
    }

    @Test
    fun aBatchThatLandsDuringARead() {
        val reading = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val reader = thread {
            SongList.load(
                SongSource {
                    reading.countDown()
                    goOn.await()
                    // Read before the batch was committed.
                    listOf(song(1, "Old"))
                },
            )
        }
        reading.await()

        SongList.applyInPlace(listOf(row(1, "New")))
        goOn.countDown()
        reader.join()

        assertThat(SongList.current()!!.single().title).isEqualTo("New")
    }

    @Test
    fun whileFrozenAReadKeepsTheOrderAndTakesItsValues() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.passStarted()

        val list = SongList.load(SongSource { listOf(song(2, "A new"), song(1, "Z new"), song(3, "Added")) })

        assertThat(list.map { it.id }).containsExactly(1L, 3L, 2L)
        assertThat(list[0].title).isEqualTo("Z new")
        assertThat(SongList.current()).isEqualTo(list)
        SongList.passFinished()
    }

    @Test
    fun aFreshReadDuringThePassSortsAndTheOrderIsFrozenAgainAfterIt() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.passStarted()

        val sorted = SongList.load(SongSource { listOf(song(2, "A"), song(1, "B")) }, fresh = true)
        val after = SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })

        assertThat(sorted.map { it.id }).containsExactly(2L, 1L)
        assertThat(after.map { it.id }).containsExactly(2L, 1L)
        SongList.passFinished()
    }

    @Test
    fun withoutAPassAReadSortsAsItAlwaysDid() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })

        val list = SongList.load(SongSource { listOf(song(2, "A"), song(1, "B")) })

        assertThat(list.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun afterAPassThatChangedRowsTheNextSortIsDueOnce() {
        SongList.load(SongSource { listOf(song(1, "A")) })
        SongList.passStarted()
        assertThat(SongList.resortDue()).isFalse()
        SongList.applyInPlace(listOf(row(1, "Z")))
        assertThat(SongList.resortDue()).describedAs("while the pass runs").isFalse()

        SongList.passFinished()
        assertThat(SongList.resortDue()).isTrue()

        SongList.load(SongSource { listOf(song(1, "Z")) }, fresh = true)
        assertThat(SongList.resortDue()).describedAs("after the sort").isFalse()
    }

    @Test
    fun aPassThatChangedNothingLeavesTheOrderFree() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.passStarted()
        SongList.passFinished()

        assertThat(SongList.resortDue()).isFalse()
        val list = SongList.load(SongSource { listOf(song(2, "A"), song(1, "B")) })
        assertThat(list.map { it.id }).containsExactly(2L, 1L)
    }
}
