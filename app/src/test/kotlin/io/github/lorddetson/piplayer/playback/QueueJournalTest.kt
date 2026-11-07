package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.playback.EntryKey.Added
import io.github.lorddetson.piplayer.playback.EntryKey.Opened
import io.github.lorddetson.piplayer.playback.EntryKey.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The edit journal replayed over the compact request's list (docs/playback.md §4.8). */
class QueueJournalTest {

    private val base = listOf(Song(1), Song(2), Song(3), Song(4))

    @Test
    fun noEditsIsTheBase() {
        assertThat(QueueJournal.replay(base, emptyList())).isEqualTo(base)
    }

    @Test
    fun insertsGoAfterTheirAnchorOrFirst() {
        val edits = listOf(
            QueueEdit.Insert(after = Song(2), entries = listOf(Added(1), Added(2)), row = 2),
            QueueEdit.Insert(after = null, entries = listOf(Added(3)), row = 0),
            QueueEdit.Insert(after = Added(2), entries = listOf(Added(4)), row = 5),
        )

        assertThat(QueueJournal.replay(base, edits))
            .containsExactly(Added(3), Song(1), Song(2), Added(1), Added(2), Added(4), Song(3), Song(4))
    }

    @Test
    fun removalsAndMovesNameTheirEntries() {
        val edits = listOf(
            QueueEdit.Remove(entry = Song(2), row = 1),
            QueueEdit.Move(entry = Song(4), after = Song(1), row = 1),
            QueueEdit.Move(entry = Song(1), after = null, row = 0),
            QueueEdit.Move(entry = Song(3), after = null, row = 0),
        )

        assertThat(QueueJournal.replay(base, edits)).containsExactly(Song(3), Song(1), Song(4))
    }

    @Test
    fun onAChangedListTheEditsStillFindTheirEntries() {
        val edits = listOf(
            QueueEdit.Insert(after = Song(3), entries = listOf(Added(1)), row = 3),
            QueueEdit.Remove(entry = Song(4), row = 4),
        )
        // A song came in at the front and song 2 went since the edits were made.
        val changed = listOf(Song(9), Song(1), Song(3), Song(4))

        assertThat(QueueJournal.replay(changed, edits)).containsExactly(Song(9), Song(1), Song(3), Added(1))
    }

    @Test
    fun anInsertWhoseAnchorIsGoneGoesToItsRow() {
        val edits = listOf(QueueEdit.Insert(after = Song(7), entries = listOf(Added(1)), row = 2))

        assertThat(QueueJournal.replay(base, edits)).containsExactly(Song(1), Song(2), Added(1), Song(3), Song(4))
        assertThat(QueueJournal.replay(listOf(Song(1)), edits)).containsExactly(Song(1), Added(1))
    }

    @Test
    fun aRemovalOrMoveOfAGoneEntryDoesNothing() {
        val edits = listOf(
            QueueEdit.Remove(entry = Song(7), row = 0),
            QueueEdit.Move(entry = Opened(0), after = null, row = 0),
        )

        assertThat(QueueJournal.replay(base, edits)).isEqualTo(base)
    }

    @Test
    fun aMoveWhoseAnchorIsGoneGoesToItsRow() {
        val edits = listOf(QueueEdit.Move(entry = Song(4), after = Song(8), row = 1))

        assertThat(QueueJournal.replay(base, edits)).containsExactly(Song(1), Song(4), Song(2), Song(3))
    }

    @Test
    fun aRemovedAddedEntryCanBeAddedAgainUnderANewNumber() {
        val edits = listOf(
            QueueEdit.Insert(after = Song(1), entries = listOf(Added(1)), row = 1),
            QueueEdit.Remove(entry = Added(1), row = 1),
            QueueEdit.Insert(after = Song(4), entries = listOf(Added(2)), row = 4),
        )

        assertThat(QueueJournal.replay(base, edits)).containsExactly(Song(1), Song(2), Song(3), Song(4), Added(2))
    }

    @Test
    fun withoutSongKeysOnlyTheEditsOfAddedEntriesApply() {
        val edits = listOf(
            QueueEdit.Insert(after = Song(3), entries = listOf(Added(1), Added(2)), row = 1),
            QueueEdit.Remove(entry = Song(1), row = 0),
            QueueEdit.Move(entry = Song(2), after = null, row = 0),
            QueueEdit.Move(entry = Added(2), after = null, row = 0),
            QueueEdit.Remove(entry = Added(1), row = 1),
        )

        assertThat(QueueJournal.replay(base, edits, songKeys = false)).containsExactly(Added(2), Song(1), Song(2), Song(3), Song(4))
    }
}
