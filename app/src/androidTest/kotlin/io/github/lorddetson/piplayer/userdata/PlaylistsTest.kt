package io.github.lorddetson.piplayer.userdata

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.TimingTest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playlists on the real user database (docs/library.md §16.1). */
@RunWith(AndroidJUnit4::class)
class PlaylistsTest {

    private val database = UserDatabase.open(TestFiles.targetContext, name = null)
    private var now = 100L
    private val playlists = Playlists(database) { now }

    /** The identities the entries refer to: a write for an identity that does not exist goes nowhere (§8.2). */
    @Before
    fun identities() = runBlocking {
        database.identities().addAll(
            (1..9).map { n ->
                TrackIdentityEntity(
                    id = n.toLong(), contentKey = ByteArray(32) { n.toByte() }, durationMs = 1_000L * n, recordingMbid = null,
                    hintVolumeName = null, hintRelativePath = null, hintDisplayName = null, hintSize = null, createdAt = 1,
                    missingSince = null,
                )
            },
        )
    }

    @After
    fun close() = database.close()

    private fun entry(n: Int, identity: Long? = n.toLong()) =
        NewEntry(identity, "external_primary", "Music/", "$n.mp3", 1_000L * n, title = "Song $n", artist = "Artist")

    private suspend fun names(id: Long) = playlists.entries(id).map { it.hintDisplayName }

    private suspend fun positions(id: Long) = playlists.entries(id).map { it.position }

    @Test
    fun aPlaylistIsCreatedRenamedAndDeletedWithItsEntries() = runBlocking<Unit> {
        val id = playlists.create("  Road trip ")
        val other = playlists.create("Road trip")
        playlists.add(id, listOf(entry(1), entry(2)))
        playlists.add(other, listOf(entry(3)))

        // Names are trimmed; two playlists may have one name.
        assertThat(playlists.playlists().map { it.name to it.entries }).containsExactly("Road trip" to 2, "Road trip" to 1)
        assertThat(playlists.playlist(id)?.createdAt).isEqualTo(100)

        now = 200
        assertThat(playlists.rename(id, "Gym")).isTrue()
        assertThat(playlists.playlist(id)?.name).isEqualTo("Gym")
        assertThat(playlists.playlist(id)?.updatedAt).isEqualTo(200)

        assertThat(playlists.delete(id)).isTrue()
        assertThat(playlists.playlist(id)).isNull()
        assertThat(playlists.entries(id)).isEmpty()
        assertThat(names(other)).containsExactly("3.mp3")
        assertThat(playlists.delete(id)).isFalse()
    }

    @Test
    fun blankNamesAreRefused() = runBlocking<Unit> {
        assertThatThrownBy { runBlocking { playlists.create("   ") } }.isInstanceOf(IllegalArgumentException::class.java)
        val id = playlists.create("A")
        assertThatThrownBy { runBlocking { playlists.rename(id, "") } }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(playlists.playlist(id)?.name).isEqualTo("A")
    }

    @Test
    fun addedSongsGoToTheEndAndOneSongMayStandTwice() = runBlocking<Unit> {
        val id = playlists.create("A")
        now = 300

        assertThat(playlists.add(id, listOf(entry(1), entry(2)))).isEqualTo(2)
        assertThat(playlists.add(id, listOf(entry(1), entry(9, identity = null)))).isEqualTo(2)

        assertThat(names(id)).containsExactly("1.mp3", "2.mp3", "1.mp3", "9.mp3")
        assertThat(positions(id)).containsExactly(0, 1, 2, 3)
        assertThat(playlists.entries(id).map { it.identityId }).containsExactly(1L, 2L, 1L, null)
        assertThat(playlists.entries(id).first().hintTitle).isEqualTo("Song 1")
        assertThat(playlists.playlist(id)?.updatedAt).isEqualTo(300)
    }

    @Test
    fun aRemovedEntryLeavesNoGapAndComesBackInItsPlace() = runBlocking<Unit> {
        val id = playlists.create("A")
        playlists.add(id, (1..4).map { entry(it) })
        val second = playlists.entries(id)[1]

        val removed = playlists.remove(second.id)

        assertThat(removed).isEqualTo(second)
        assertThat(names(id)).containsExactly("1.mp3", "3.mp3", "4.mp3")
        assertThat(positions(id)).containsExactly(0, 1, 2)

        playlists.restore(removed!!)
        assertThat(playlists.entries(id)).containsExactlyElementsOf(playlists.entries(id).sortedBy { it.position })
        assertThat(names(id)).containsExactly("1.mp3", "2.mp3", "3.mp3", "4.mp3")
        assertThat(positions(id)).containsExactly(0, 1, 2, 3)
        assertThat(playlists.remove(-5)).isNull()
    }

    @Test
    fun anEntryMovesUpAndDownWithoutGaps() = runBlocking<Unit> {
        val id = playlists.create("A")
        playlists.add(id, (1..5).map { entry(it) })
        val entries = playlists.entries(id)

        assertThat(playlists.move(entries[4].id, 1)).isTrue()
        assertThat(names(id)).containsExactly("1.mp3", "5.mp3", "2.mp3", "3.mp3", "4.mp3")
        assertThat(playlists.move(entries[0].id, 4)).isTrue()
        assertThat(names(id)).containsExactly("5.mp3", "2.mp3", "3.mp3", "4.mp3", "1.mp3")
        // Past the end it stops at the end; the same place changes nothing.
        assertThat(playlists.move(entries[1].id, 99)).isTrue()
        assertThat(names(id)).containsExactly("5.mp3", "3.mp3", "4.mp3", "1.mp3", "2.mp3")
        assertThat(playlists.move(entries[1].id, 4)).isFalse()
        assertThat(positions(id)).containsExactly(0, 1, 2, 3, 4)
    }

    @Test
    fun anEntryMovesByStepsFromWhereItStandsNow() = runBlocking<Unit> {
        // "Move up" twice before the page shows the first move: each step counts from the entry's place in the database.
        val id = playlists.create("A")
        playlists.add(id, (1..4).map { entry(it) })
        val last = playlists.entries(id)[3]

        assertThat(playlists.moveBy(last.id, -1)).isTrue()
        assertThat(playlists.moveBy(last.id, -1)).isTrue()
        assertThat(names(id)).containsExactly("1.mp3", "4.mp3", "2.mp3", "3.mp3")
        assertThat(playlists.moveBy(last.id, 1)).isTrue()
        assertThat(names(id)).containsExactly("1.mp3", "2.mp3", "4.mp3", "3.mp3")
        // At an end there is nowhere to go: nothing moves, which is no failure.
        val first = playlists.entries(id)[0]
        assertThat(playlists.moveBy(first.id, -1)).isFalse()
        assertThat(playlists.moveBy(-5, 1)).isFalse()
        assertThat(positions(id)).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun playlistsRaiseNoChangeCounter() = runBlocking<Unit> {
        // Nothing of a playlist is mirrored (docs/library.md §16.5).
        val id = playlists.create("A")
        playlists.add(id, listOf(entry(1), entry(2)))
        playlists.move(playlists.entries(id)[0].id, 1)
        playlists.rename(id, "B")
        playlists.remove(playlists.entries(id)[0].id)
        playlists.delete(id)

        assertThat(database.user().changeCounter()).isZero()
    }

    @Test
    fun selectedEntriesAreRemovedTogetherWithoutGapsAndPutBackAtTheirPlaces() = runBlocking<Unit> {
        // Song 2 stands twice; one copy, an entry without an identity and the last entry go (docs/library.md §20.3).
        val id = playlists.create("A")
        playlists.add(id, listOf(entry(1), entry(2), entry(3, identity = null), entry(2), entry(4), entry(5)))
        val other = playlists.create("B")
        playlists.add(other, listOf(entry(6)))
        val before = playlists.entries(id)
        val chosen = listOf(before[5].id, before[1].id, before[2].id)
        now = 400

        val removed = playlists.removeAll(chosen + 999L)

        assertThat(removed.map { it.id }).containsExactly(before[1].id, before[2].id, before[5].id)
        assertThat(names(id)).containsExactly("1.mp3", "2.mp3", "4.mp3")
        assertThat(playlists.entries(id).map { it.id }).containsExactly(before[0].id, before[3].id, before[4].id)
        assertThat(positions(id)).containsExactly(0, 1, 2)
        assertThat(playlists.playlist(id)?.updatedAt).isEqualTo(400)
        assertThat(names(other)).containsExactly("6.mp3")

        playlists.restoreAll(removed)

        assertThat(playlists.entries(id).map { it.id }).containsExactlyElementsOf(before.map { it.id })
        assertThat(positions(id)).containsExactly(0, 1, 2, 3, 4, 5)
        // The identities are checked again: they still exist.
        assertThat(playlists.entries(id).map { it.identityId }).containsExactlyElementsOf(before.map { it.identityId })
    }

    @Test
    fun entriesPutBackIntoAShorterPlaylistGoToItsEndAndAGoneIdentityIsLeftOut() = runBlocking<Unit> {
        val id = playlists.create("A")
        playlists.add(id, listOf(entry(1), entry(2), entry(3), entry(4)))
        val before = playlists.entries(id)
        val removed = playlists.removeAll(listOf(before[2].id, before[3].id))
        // Meanwhile the first entry goes too, and the identity of song 4 is deleted.
        playlists.remove(before[0].id)
        database.identities().delete(listOf(4L))

        playlists.restoreAll(removed)

        assertThat(names(id)).containsExactly("2.mp3", "3.mp3", "4.mp3")
        assertThat(positions(id)).containsExactly(0, 1, 2)
        assertThat(playlists.entries(id).last().identityId).isNull()
    }

    @Test
    @TimingTest
    fun thousandScatteredEntriesGoBackIntoATwentyThousandEntryPlaylistWithinASecond() = runBlocking<Unit> {
        // An imported M3U8 of the whole library, and an undo of 1 000 entries removed from all over it (library.md §20.3).
        val id = playlists.create("Library")
        playlists.add(id, List(20_000) { entry(it % 9 + 1) })
        val before = playlists.entries(id).map { it.id }
        val chosen = before.filterIndexed { index, _ -> index % 20 == 7 }

        val times = List(5) {
            val removed = playlists.removeAll(chosen)
            val start = SystemClock.elapsedRealtime()
            playlists.restoreAll(removed)
            (SystemClock.elapsedRealtime() - start).also { assertThat(playlists.entries(id).map { entry -> entry.id }).isEqualTo(before) }
        }

        assertThat(positions(id)).isEqualTo((0 until 20_000).toList())
        Log.i("PlaylistsTest", "1 000 of 20 000 entries put back: $times ms")
        assertThat(times.sorted()[2]).describedAs("median of $times ms").isLessThan(1_000)
    }
}
