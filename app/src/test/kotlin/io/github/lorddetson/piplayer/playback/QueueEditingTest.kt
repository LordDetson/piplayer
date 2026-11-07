package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.Song as LibrarySong
import io.github.lorddetson.piplayer.playback.EntryKey.Added
import io.github.lorddetson.piplayer.playback.EntryKey.Opened
import io.github.lorddetson.piplayer.playback.EntryKey.Song
import java.util.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Where added songs go, what the journal records and what the up-next run holds (docs/playback.md §9). */
class QueueEditingTest {

    private fun track(fileId: Long) = TrackRef(fileId, null, null, null, null)

    private val request = QueueRequest(startSongId = 1, startRow = 0)

    private fun opened(row: Int, name: String = "o$row") = SavedOpenItem(row, OpenedFile("content://open/$name", name))

    private fun state(
        openItems: List<SavedOpenItem> = emptyList(),
        edits: QueueEdits = QueueEdits(),
        library: QueueRequest? = request,
    ) = QueueState(library, openItems, "lib", edits)

    private fun songs(vararg ids: Long): List<EntryKey> = ids.map { Song(it) }

    @Test
    fun playNextGoesRightAfterTheCurrentSongAndStartsTheRun() {
        val view = QueueView(songs(1, 2, 3, 4, 5), current = 1)

        val result = QueueEditing.insert(state(), view, listOf(track(7), track(8)), next = true)

        assertThat(result.row).isEqualTo(2)
        assertThat(result.position).isNull()
        assertThat(result.keys).containsExactly(Added(1), Added(2))
        assertThat(result.state.edits.added).containsExactly(AddedEntry(1, track(7)), AddedEntry(2, track(8)))
        assertThat(result.state.edits.journal).containsExactly(QueueEdit.Insert(Song(2), listOf(Added(1), Added(2)), row = 2))
        assertThat(result.state.edits.upNext).containsExactlyInAnyOrder(Added(1), Added(2))
        assertThat(result.state.edits.nextNumber).isEqualTo(3)
        assertThat(result.state.library).isEqualTo(request)
    }

    @Test
    fun addToQueueGoesAfterTheUpNextRunAndPlayNextBeforeIt() {
        val first = QueueEditing.insert(state(), QueueView(songs(1, 2, 3, 4, 5), current = 1), listOf(track(7), track(8)), next = true)
        val afterFirst = listOf(Song(1), Song(2), Added(1), Added(2), Song(3), Song(4), Song(5))

        val added = QueueEditing.insert(first.state, QueueView(afterFirst, current = 1), listOf(track(9)), next = false)

        assertThat(added.row).isEqualTo(4)
        assertThat(added.keys).containsExactly(Added(3))
        assertThat(added.state.edits.journal.last()).isEqualTo(QueueEdit.Insert(Added(2), listOf(Added(3)), row = 4))
        assertThat(added.state.edits.upNext).containsExactlyInAnyOrder(Added(1), Added(2), Added(3))

        val afterAdded = listOf(Song(1), Song(2), Added(1), Added(2), Added(3), Song(3), Song(4), Song(5))
        val next = QueueEditing.insert(added.state, QueueView(afterAdded, current = 1), listOf(track(10)), next = true)

        assertThat(next.row).isEqualTo(2)
        assertThat(next.state.edits.upNext).containsExactlyInAnyOrder(Added(1), Added(2), Added(3), Added(4))
    }

    @Test
    fun withoutARunAddToQueueGoesRightAfterTheCurrentSong() {
        val result = QueueEditing.insert(state(), QueueView(songs(1, 2, 3), current = 0), listOf(track(9)), next = false)

        assertThat(result.row).isEqualTo(1)
        assertThat(result.state.edits.journal).containsExactly(QueueEdit.Insert(Song(1), listOf(Added(1)), row = 1))
    }

    @Test
    fun anAddedSongThatIsNotRightAfterTheCurrentOneIsNoRun() {
        // Added 1 is in the run's set but a library song stands between it and the current song.
        val edits = QueueEdits(added = listOf(AddedEntry(1, track(7))), upNext = listOf(Added(1)), nextNumber = 2)
        val view = QueueView(listOf(Song(1), Song(2), Added(1), Song(3)), current = 0)

        val result = QueueEditing.insert(state(edits = edits), view, listOf(track(9)), next = false)

        assertThat(result.row).isEqualTo(1)
    }

    @Test
    fun journalRowsLeaveOutOpenedFilesWhoseRowsMove() {
        val view = QueueView(listOf(Song(1), Song(2), Opened(0), Song(3)), current = 1)

        val result = QueueEditing.insert(state(listOf(opened(2))), view, listOf(track(7)), next = true)

        assertThat(result.row).isEqualTo(2)
        assertThat(result.state.edits.journal).containsExactly(QueueEdit.Insert(Song(2), listOf(Added(1)), row = 2))
        assertThat(result.state.openItems.map { it.index }).containsExactly(3)
    }

    @Test
    fun songsAddedAfterAnOpenedFileAnchorOnTheEntryBeforeIt() {
        val view = QueueView(listOf(Song(1), Opened(0), Song(2)), current = 1)

        val result = QueueEditing.insert(state(listOf(opened(1))), view, listOf(track(7)), next = true)

        assertThat(result.row).isEqualTo(2)
        assertThat(result.state.edits.journal).containsExactly(QueueEdit.Insert(Song(1), listOf(Added(1)), row = 1))
        assertThat(result.state.openItems.map { it.index }).containsExactly(1)

        val first = QueueEditing.insert(state(listOf(opened(0))), QueueView(listOf(Opened(0), Song(1)), current = 0), listOf(track(7)), next = true)
        assertThat(first.state.edits.journal).containsExactly(QueueEdit.Insert(null, listOf(Added(1)), row = 0))
    }

    @Test
    fun underShuffleSongsGoToTheSamePlaceOfThePlayOrder() {
        // Rows in play order: song 1, song 3 (playing), song 5, song 2, song 4.
        val view = QueueView(songs(1, 2, 3, 4, 5), current = 2, order = intArrayOf(0, 2, 4, 1, 3))

        val result = QueueEditing.insert(state(), view, listOf(track(7)), next = true)

        assertThat(result.row).isEqualTo(3)
        assertThat(result.position).isEqualTo(2)
    }

    @Test
    fun underShuffleAddToQueueGoesAfterTheRunInBothOrders() {
        val edits = QueueEdits(added = listOf(AddedEntry(1, track(7))), upNext = listOf(Added(1)), nextNumber = 2)
        val view = QueueView(listOf(Song(1), Song(2), Song(3), Added(1), Song(4)), current = 2, order = intArrayOf(0, 2, 3, 4, 1))

        val result = QueueEditing.insert(state(edits = edits), view, listOf(track(8)), next = false)

        assertThat(result.row).isEqualTo(4)
        assertThat(result.position).isEqualTo(3)
        assertThat(result.keys).containsExactly(Added(2))
    }

    @Test
    fun anEmptyPlayerGetsAQueueOfAddedSongsOnly() {
        val queue = QueueEditing.added("lib", listOf(track(7), track(8)))

        assertThat(queue.library).isNull()
        assertThat(queue.openItems).isEmpty()
        assertThat(queue.libraryId).isEqualTo("lib")
        assertThat(queue.edits).isEqualTo(QueueEdits(added = listOf(AddedEntry(1, track(7)), AddedEntry(2, track(8))), nextNumber = 3))
    }

    @Test
    fun removingAnEntryRecordsItAndItLeavesTheRun() {
        val edits = QueueEdits(added = listOf(AddedEntry(1, track(7))), upNext = listOf(Added(1)), nextNumber = 2)
        val view = QueueView(listOf(Song(1), Song(2), Added(1), Opened(0), Song(3)), current = 1)

        val result = QueueEditing.remove(state(listOf(opened(3)), edits), view, row = 2)!!

        assertThat(result.edits.journal).containsExactly(QueueEdit.Remove(Added(1), row = 2))
        assertThat(result.edits.upNext).isEmpty()
        assertThat(result.openItems.map { it.index }).containsExactly(2)

        val song = QueueEditing.remove(state(listOf(opened(3)), edits), view, row = 4)!!
        assertThat(song.edits.journal).containsExactly(QueueEdit.Remove(Song(3), row = 3))
    }

    @Test
    fun removingAnOpenedFileChangesOnlyTheOpenedFiles() {
        val view = QueueView(listOf(Song(1), Opened(0), Song(2), Opened(1)), current = 0)

        val result = QueueEditing.remove(state(listOf(opened(1, "a"), opened(3, "b"))), view, row = 1)!!

        assertThat(result.edits.journal).isEmpty()
        assertThat(result.openItems).containsExactly(opened(3, "b").copy(index = 2))
    }

    @Test
    fun removingTheLastOpenedFileOfAQueueWithoutLibraryLeavesTheAddedEntries() {
        val edits = QueueEdits(added = listOf(AddedEntry(1, track(7))), nextNumber = 2)
        val view = QueueView(listOf(Opened(0), Added(1)), current = 1)

        val result = QueueEditing.remove(state(listOf(opened(0)), edits, library = null), view, row = 0)!!

        assertThat(result.openItems).isEmpty()
        assertThat(result.edits.added).hasSize(1)
    }

    @Test
    fun movingAnEntryRecordsItsNewNeighbour() {
        val view = QueueView(songs(1, 2, 3, 4), current = 0)

        val result = QueueEditing.move(state(), view, from = 0, to = 2)

        assertThat(result.edits.journal).containsExactly(QueueEdit.Move(Song(1), after = Song(3), row = 2))

        val first = QueueEditing.move(state(listOf(opened(1))), QueueView(listOf(Song(1), Opened(0), Song(2), Song(3)), 0), from = 3, to = 0)
        assertThat(first.edits.journal).containsExactly(QueueEdit.Move(Song(3), after = null, row = 0))
        assertThat(first.openItems.map { it.index }).containsExactly(2)
    }

    @Test
    fun movingAnOpenedFileChangesOnlyItsRow() {
        val view = QueueView(listOf(Song(1), Opened(0), Song(2), Song(3)), current = 0)

        val result = QueueEditing.move(state(listOf(opened(1))), view, from = 1, to = 3)

        assertThat(result.edits.journal).isEmpty()
        assertThat(result.openItems.map { it.index }).containsExactly(3)
    }

    @Test
    fun anEntryPlayedOrPassedLeavesTheRun() {
        val edits = QueueEdits(
            added = listOf(AddedEntry(1, track(7)), AddedEntry(2, track(8))),
            upNext = listOf(Added(1), Added(2)),
            nextNumber = 3,
        )
        val keys = listOf(Song(1), Added(1), Added(2), Song(2))

        assertThat(QueueEditing.played(state(edits = edits), QueueView(keys, current = 1)).edits.upNext).containsExactly(Added(2))
        assertThat(QueueEditing.played(state(edits = edits), QueueView(keys, current = 3)).edits.upNext).isEmpty()
        // In the play order song 2, song 1, added 2 (playing), added 1: added 1 is still to come.
        val shuffled = QueueView(keys, current = 2, order = intArrayOf(3, 0, 2, 1))
        assertThat(QueueEditing.played(state(edits = edits), shuffled).edits.upNext).containsExactly(Added(1))
    }

    @Test
    fun theRunsRowsAndThePlaceWhereOtherSongsJoin() {
        val edits = QueueEdits(
            added = listOf(AddedEntry(1, track(7)), AddedEntry(2, track(8)), AddedEntry(3, track(9))),
            upNext = listOf(Added(1), Added(2), Added(3)),
            nextNumber = 4,
        )
        val keys = listOf(Song(1), Added(1), Added(2), Song(2), Added(3))

        assertThat(QueueEditing.upNextRows(state(edits = edits), QueueView(keys, current = 0))).containsExactly(1, 2)
        assertThat(QueueEditing.joinAfter(state(edits = edits), QueueView(keys, current = 0))).isEqualTo(2)
        // In the play order song 2, song 1 (playing), added 1, added 2, added 3.
        val shuffled = QueueView(keys, current = 0, order = intArrayOf(3, 0, 1, 2, 4))
        assertThat(QueueEditing.joinAfter(state(edits = edits), shuffled)).isEqualTo(4)
        assertThat(QueueEditing.joinAfter(state(), QueueView(keys, current = 3))).isEqualTo(3)
    }

    @Test
    fun theRebuiltQueueEqualsTheEditedOne() {
        repeat(30) { seed ->
            val random = Random(seed.toLong())
            val library = (1L..12L).map(::song)
            val openItems = listOf(opened(2, "a"), opened(7, "b"))
            var current = QueueState(request, openItems, "lib")
            val keys = QueueRestore.withOpened(library.map { QueueSlot.Library(it) }, snapshot(openItems), openItems)
                .map { slotKey(it, openItems) }
                .toMutableList()
            val addedSongs = HashMap<Long, LibrarySong>()
            repeat(25) {
                val playing = random.nextInt(keys.size)
                val view = QueueView(keys.toList(), playing)
                when (random.nextInt(3)) {
                    0 -> {
                        val ids = List(1 + random.nextInt(2)) { 100L + random.nextInt(50) }
                        val result = QueueEditing.insert(current, view, ids.map(::track), next = random.nextBoolean())
                        keys.addAll(result.row, result.keys)
                        result.keys.zip(ids).forEach { (key, id) -> addedSongs[key.number] = song(id) }
                        current = result.state
                    }
                    1 -> if (keys.size > 2) {
                        val row = random.nextInt(keys.size)
                        current = QueueEditing.remove(current, view, row)!!
                        keys.removeAt(row)
                    }
                    else -> {
                        val from = random.nextInt(keys.size)
                        val to = random.nextInt(keys.size)
                        current = QueueEditing.move(current, view, from, to)
                        keys.add(to, keys.removeAt(from))
                    }
                }
                // Opened keys name their place in the list of opened files, which a removal shortens.
                renumberOpened(keys, current.openItems)
            }

            val entries = QueueRebuild.entries(library, current.edits, addedSongs, songKeys = true)
            val rebuilt = QueueRestore.withOpened(entries, snapshot(current.openItems), current.openItems)

            assertThat(rebuilt.map { slotKey(it, current.openItems) }).describedAs("seed $seed").isEqualTo(keys)
        }
    }

    private fun song(id: Long) = LibrarySong(id, "content://media/$id", "Song $id", null, null, 1_000)

    private fun snapshot(openItems: List<SavedOpenItem>) = PlaybackSnapshot(
        queue = request,
        openItems = openItems,
        songId = 1,
        row = 0,
        positionMs = 0,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = null,
        artist = null,
        album = null,
        durationMs = 0,
        uri = null,
    )

    private fun slotKey(slot: QueueSlot, openItems: List<SavedOpenItem>): EntryKey = when (slot) {
        is QueueSlot.Library -> Song(slot.song.id)
        is QueueSlot.Added -> Added(slot.number)
        is QueueSlot.Open -> Opened(openItems.indexOfFirst { it.uri == slot.item.uri })
    }

    /** The opened entries of [keys] by the place of their file in [openItems], whose rows must be theirs. */
    private fun renumberOpened(keys: MutableList<EntryKey>, openItems: List<SavedOpenItem>) {
        for (row in keys.indices) if (keys[row] is Opened) keys[row] = Opened(openItems.indexOfFirst { it.index == row })
    }

    @Test
    fun removingTheOnlyEntryLeavesNoQueue() {
        val openedOnly = state(listOf(opened(0)), library = null)

        assertThat(QueueEditing.remove(openedOnly, QueueView(listOf(Opened(0)), current = 0), row = 0)).isNull()
        assertThat(QueueEditing.remove(state(), QueueView(songs(1), current = 0), row = 0)).isNull()
        assertThat(QueueEditing.removeRange(state(listOf(opened(1))), QueueView(listOf(Song(1), Opened(0)), 0), 0, 2)).isNull()
    }

    @Test
    fun aRangeRemovedAcrossOpenedFilesKeepsTheOthers() {
        val view = QueueView(listOf(Song(1), Opened(0), Song(2), Opened(1), Song(3)), current = 0)

        val result = QueueEditing.removeRange(state(listOf(opened(1, "a"), opened(3, "b"))), view, from = 1, to = 4)!!

        assertThat(result.openItems).isEmpty()
        assertThat(result.edits.journal).containsExactly(QueueEdit.Remove(Song(2), row = 1))
    }

    @Test
    fun aRangeMovedAcrossOpenedFilesKeepsEachFileItsUri() {
        val view = QueueView(listOf(Song(1), Opened(0), Song(2), Opened(1), Song(3)), current = 0)

        // Song 1 and file a move to start at row 3: song 2, file b, song 3, song 1, file a.
        val result = QueueEditing.moveRange(state(listOf(opened(1, "a"), opened(3, "b"))), view, from = 0, to = 2, newIndex = 3)

        assertThat(result.openItems).containsExactly(opened(1, "b"), opened(4, "a"))
        assertThat(result.edits.journal).containsExactly(QueueEdit.Move(Song(1), after = Song(3), row = 2))

        // Back the other way: file b and song 3 move to the front.
        val back = QueueEditing.moveRange(state(listOf(opened(1, "a"), opened(3, "b"))), view, from = 3, to = 5, newIndex = 0)
        assertThat(back.openItems).containsExactly(opened(0, "b"), opened(3, "a"))
        assertThat(back.edits.journal).containsExactly(QueueEdit.Move(Song(3), after = null, row = 0))
    }
}
