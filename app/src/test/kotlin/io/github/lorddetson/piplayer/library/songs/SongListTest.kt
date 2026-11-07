package io.github.lorddetson.piplayer.library.songs

import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SongListTest {

    private fun song(id: Long, title: String) = Song(id, "file:///$id.mp3", title, null, null, null)

    @AfterEach
    fun forget() = SongList.forget()

    @Test
    fun `the list keeps the order its source gives`() {
        // The library database sorts (docs/library.md §1.4); sorting again would cost a tap 20 000 normalizations.
        SongList.load(SongSource { listOf(song(2, "B"), song(1, "A")) })

        assertThat(SongList.current()!!.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun `an older read that ends last does not replace a newer one`() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val older = thread {
            SongList.load(SongSource {
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
                listOf(song(1, "Deleted since"))
            })
        }
        // The source is asked only once the read has started, so the older read is then surely the older one; a sleep
        // was not enough on a busy machine.
        assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue()

        SongList.load(SongSource { listOf(song(2, "Newer")) })
        release.countDown()
        older.join(5_000)

        assertThat(SongList.current()!!.map { it.title }).containsExactly("Newer")
    }

    private val album = SongQuery.album("album key")
    private val byLength = SongQuery.SONGS.copy(sort = SongSort.LENGTH)

    @Test
    fun `each list is kept apart from the others`() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.load(SongSource { listOf(song(2, "B")) }, query = album)

        assertThat(SongList.current()!!.map { it.id }).containsExactly(1L, 2L)
        assertThat(SongList.current(album)!!.map { it.id }).containsExactly(2L)
        assertThat(SongList.current(byLength)).isNull()
    }

    @Test
    fun `only the last few lists are kept`() {
        val queries = List(SongList.MAX_KEPT + 1) { SongQuery.album("album $it") }
        for (query in queries) SongList.load(SongSource { listOf(song(1, "A")) }, query = query)

        assertThat(SongList.current(queries.first())).isNull()
        assertThat(queries.drop(1).map { SongList.current(it) }).doesNotContainNull()
    }

    @Test
    fun `a batch of the tag pass changes the rows of every kept list in place`() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.load(SongSource { listOf(song(2, "B"), song(1, "A")) }, query = byLength)

        SongList.applyInPlace(listOf(SongRow(fileId = 2, mediaStoreId = 2, title = "Zulu", artist = null, album = null, durationMs = null)))

        assertThat(SongList.current()!!.map { it.title }).containsExactly("A", "Zulu")
        assertThat(SongList.current(byLength)!!.map { it.title }).containsExactly("Zulu", "A")
    }

    @Test
    fun `after a pass that changed rows each kept list is sorted anew once`() {
        SongList.load(SongSource { listOf(song(1, "A")) })
        SongList.load(SongSource { listOf(song(1, "A")) }, query = album)
        SongList.passStarted()
        SongList.applyInPlace(listOf(SongRow(1, 1, "Zulu", null, null, null)))
        SongList.passFinished()

        assertThat(SongList.resortDue()).isTrue()
        assertThat(SongList.resortDue(album)).isTrue()

        SongList.load(SongSource { listOf(song(1, "Zulu")) }, query = album, fresh = true)

        assertThat(SongList.resortDue(album)).isFalse()
        assertThat(SongList.resortDue()).isTrue()
    }

    @Test
    fun `while the pass runs a list keeps its order, and a new list is read as it comes`() {
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        SongList.passStarted()

        val kept = SongList.load(SongSource { listOf(song(2, "B"), song(1, "C")) })
        val new = SongList.load(SongSource { listOf(song(2, "B"), song(1, "C")) }, query = album)

        assertThat(kept.map { it.id }).containsExactly(1L, 2L)
        assertThat(new.map { it.id }).containsExactly(2L, 1L)
    }

    @Test
    fun `a list a screen or the queue holds is not pushed out by newer ones`() {
        val owner = Any()
        SongList.load(SongSource { listOf(song(1, "A")) }, query = album)
        SongList.hold(owner, album)
        val searches = List(SongList.MAX_KEPT + 2) { SongQuery.SONGS.copy(search = "s$it") }
        for (query in searches) SongList.load(SongSource { listOf(song(2, "B")) }, query = query)

        assertThat(SongList.current(album)).isNotNull()
        assertThat(SongList.current(searches.first())).isNull()

        // Let go, it is the oldest once as many newer lists are read as are kept.
        SongList.hold(owner, null)
        repeat(SongList.MAX_KEPT) { SongList.load(SongSource { listOf(song(3, "C")) }, query = SongQuery.artist("b$it")) }

        assertThat(SongList.current(album)).isNull()
    }

    @Test
    fun `a holder that moves to another list lets the old one go`() {
        val owner = Any()
        SongList.load(SongSource { listOf(song(1, "A")) }, query = album)
        SongList.hold(owner, album)
        SongList.hold(owner, byLength)
        repeat(SongList.MAX_KEPT) { SongList.load(SongSource { listOf(song(2, "B")) }, query = SongQuery.artist("a$it")) }

        assertThat(SongList.current(album)).isNull()
    }
}
