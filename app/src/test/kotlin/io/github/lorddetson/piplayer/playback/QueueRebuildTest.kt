package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.EntryKey.Added
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The restored queue from the request's list, the journal and the added entries (docs/playback.md §9.4). */
class QueueRebuildTest {

    private fun song(id: Long) = Song(id, "content://media/$id", "Song $id", null, null, 1_000)

    private fun track(fileId: Long) = TrackRef(fileId, null, null, null, null)

    private val library = listOf(song(1), song(2), song(3), song(4))

    private val edits = QueueEdits(
        added = listOf(AddedEntry(1, track(9)), AddedEntry(2, track(8))),
        journal = listOf(
            QueueEdit.Insert(after = EntryKey.Song(2), entries = listOf(Added(1), Added(2)), row = 2),
            QueueEdit.Remove(entry = EntryKey.Song(3), row = 4),
            QueueEdit.Move(entry = EntryKey.Song(1), after = Added(2), row = 3),
        ),
        upNext = listOf(Added(1)),
        nextNumber = 3,
    )

    @Test
    fun theJournalAndTheAddedEntriesMakeTheQueue() {
        val entries = QueueRebuild.entries(library, edits, mapOf(1L to song(9), 2L to song(8)), songKeys = true)

        assertThat(entries).containsExactly(
            QueueSlot.Library(song(2)),
            QueueSlot.Added(1, song(9)),
            QueueSlot.Added(2, song(8)),
            QueueSlot.Library(song(1)),
            QueueSlot.Library(song(4)),
        )
    }

    @Test
    fun anAddedEntryWhoseSongIsGoneIsLeftOut() {
        val entries = QueueRebuild.entries(library, edits, mapOf(2L to song(8)), songKeys = true)

        assertThat(entries).containsExactly(
            QueueSlot.Library(song(2)),
            QueueSlot.Added(2, song(8)),
            QueueSlot.Library(song(1)),
            QueueSlot.Library(song(4)),
        )
    }

    @Test
    fun underAnotherLibraryIdOnlyTheAddedEntriesStayAtTheirRows() {
        val entries = QueueRebuild.entries(library, edits, mapOf(1L to song(9), 2L to song(8)), songKeys = false)

        assertThat(entries).containsExactly(
            QueueSlot.Library(song(1)),
            QueueSlot.Library(song(2)),
            QueueSlot.Added(1, song(9)),
            QueueSlot.Added(2, song(8)),
            QueueSlot.Library(song(3)),
            QueueSlot.Library(song(4)),
        )
    }

    @Test
    fun aQueueOfAddedSongsOnlyHasNoLibrary() {
        val only = QueueEdits(added = listOf(AddedEntry(1, track(9)), AddedEntry(2, track(8))), nextNumber = 3)

        val entries = QueueRebuild.entries(emptyList(), only, mapOf(1L to song(9), 2L to song(8)), songKeys = true)

        assertThat(entries).containsExactly(QueueSlot.Added(1, song(9)), QueueSlot.Added(2, song(8)))
    }

    @Test
    fun foldingMakesEveryEntryButTheOpenedFilesAnAddedOne() {
        // A file the library has no reference for keeps its saved one, then one of its file ID alone.
        val opened = SavedOpenItem(1, OpenedFile("content://open/x", "x"))
        val saved = TrackRef(9, identityId = 5, contentKey = "ab", place = Place("v", "Music/", "9.mp3"), durationMs = 1_000)
        val slots = listOf(QueueSlot.Library(song(1)), QueueSlot.Open(opened), QueueSlot.Added(1, song(9)), QueueSlot.Library(song(2)))
        val before = QueueEdits(added = listOf(AddedEntry(1, saved)), journal = edits.journal, upNext = listOf(Added(1)), nextNumber = 2)

        val (folded, after) = QueueRebuild.fold(slots, before) { id -> if (id == 2L) null else track(id) }

        assertThat(folded).containsExactly(
            QueueSlot.Added(2, song(1)),
            QueueSlot.Open(opened),
            QueueSlot.Added(3, song(9)),
            QueueSlot.Added(4, song(2)),
        )
        // The library's reference now wins over the saved one, which may name a file of another library ID.
        assertThat(after).isEqualTo(
            QueueEdits(
                added = listOf(AddedEntry(2, track(1)), AddedEntry(3, track(9)), AddedEntry(4, track(2))),
                journal = emptyList(),
                upNext = listOf(Added(3)),
                nextNumber = 5,
            ),
        )
    }

    @Test
    fun anAddedEntryWithoutAReferenceInTheLibraryKeepsItsSavedOne() {
        val saved = TrackRef(9, identityId = 5, contentKey = "ab", place = null, durationMs = 1_000)
        val before = QueueEdits(added = listOf(AddedEntry(1, saved)), nextNumber = 2)

        val (_, after) = QueueRebuild.fold(listOf(QueueSlot.Added(1, song(9))), before) { null }

        assertThat(after.added).containsExactly(AddedEntry(2, saved))
    }

    @Test
    fun aQueueIsFoldedOverTwoHundredEditsOrUnderAnotherLibraryId() {
        fun edits(count: Int) = QueueEdits(journal = List(count) { QueueEdit.Remove(EntryKey.Song(it + 1L), 0) })

        assertThat(QueueRebuild.mustFold(edits(QueueJournal.MAX_EDITS), songKeys = true)).isFalse()
        assertThat(QueueRebuild.mustFold(edits(QueueJournal.MAX_EDITS + 1), songKeys = true)).isTrue()
        assertThat(QueueRebuild.mustFold(edits(1), songKeys = false)).isTrue()
        assertThat(QueueRebuild.mustFold(QueueEdits(), songKeys = false)).isFalse()
    }
}
