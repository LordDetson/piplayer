package io.github.lorddetson.piplayer.userdata

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Playlists on the real user database (docs/library.md §16.1). */
@RunWith(AndroidJUnit4::class)
class PlaylistsTest {

    private val database = UserDatabase.open(TestFiles.targetContext, name = null)
    private var now = 100L
    private val playlists = Playlists(database) { now }

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
}
