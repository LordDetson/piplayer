package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.Song

/**
 * A queue as its edits see it: the entries by row, the current row, and while shuffle is on the play [order] (the rows
 * in the order they play).
 */
class QueueView(val keys: List<EntryKey>, val current: Int, val order: IntArray? = null)

/**
 * Where songs added to a queue go: the list [row] of the first, the play [position] of the first while shuffle is on,
 * the [keys] they get, and the queue's [state] after.
 */
data class Insertion(val row: Int, val position: Int?, val keys: List<EntryKey.Added>, val state: QueueState)

/** The edits of a queue and the state each leaves (docs/playback.md §9). Pure: the service applies them to the player. */
object QueueEditing {

    /** A new queue of [tracks], all added entries, for an empty player (docs/playback.md §9.1). */
    fun added(libraryId: String?, tracks: List<TrackRef>): QueueState = QueueState(
        library = null,
        openItems = emptyList(),
        libraryId = libraryId,
        edits = QueueEdits(added = tracks.mapIndexed { index, track -> AddedEntry(index + 1L, track) }, nextNumber = tracks.size + 1L),
    )

    /** [tracks] added right after the current entry ([next]) or after the up-next run (docs/playback.md §9.1). */
    fun insert(state: QueueState, view: QueueView, tracks: List<TrackRef>, next: Boolean): Insertion {
        val edits = state.edits
        val upNext = edits.upNext.toHashSet<EntryKey>()
        val row = view.current + 1 + if (next) 0 else listRun(view, upNext)
        val position = view.order?.let { order ->
            val current = order.indexOf(view.current)
            current + 1 + if (next) 0 else playRun(view.keys, order, current, upNext)
        }
        val keys = tracks.indices.map { EntryKey.Added(edits.nextNumber + it) }
        val newKeys = view.keys.toMutableList().apply { addAll(row, keys) }
        val insert = QueueEdit.Insert(anchorBefore(newKeys, row), keys, laneRow(newKeys, row))
        val newEdits = edits.copy(
            added = edits.added + keys.zip(tracks) { key, track -> AddedEntry(key.number, track) },
            journal = edits.journal + insert,
            upNext = if (next) keys + edits.upNext else edits.upNext + keys,
            nextNumber = edits.nextNumber + tracks.size,
        )
        return Insertion(row, position, keys, state.copy(openItems = openRows(state.openItems, newKeys), edits = newEdits))
    }

    /**
     * [tracks] added by a controller as the entries [keys] at list [row], where Media3 puts them (docs/playback.md §10.3):
     * not part of the up-next run.
     */
    fun insertAt(state: QueueState, view: QueueView, row: Int, keys: List<EntryKey.Added>, tracks: List<TrackRef>): QueueState {
        require(keys.size == tracks.size && keys.isNotEmpty())
        val edits = state.edits
        val newKeys = view.keys.toMutableList().apply { addAll(row, keys) }
        val insert = QueueEdit.Insert(anchorBefore(newKeys, row), keys, laneRow(newKeys, row))
        val newEdits = edits.copy(
            added = edits.added + keys.zip(tracks) { key, track -> AddedEntry(key.number, track) },
            journal = edits.journal + insert,
            nextNumber = maxOf(edits.nextNumber, keys.maxOf { it.number } + 1),
        )
        return state.copy(openItems = openRows(state.openItems, newKeys), edits = newEdits)
    }

    /** The entries from [from] to [to] removed; null when that empties the queue. */
    fun removeRange(state: QueueState, view: QueueView, from: Int, to: Int): QueueState? {
        var result = state
        val keys = view.keys.toMutableList()
        for (row in to - 1 downTo from) {
            result = remove(result, QueueView(keys.toList(), view.current), row) ?: return null
            keys.removeAt(row)
            renumberOpened(keys)
        }
        return result
    }

    /** The entries from [from] to [to] moved in the list to start at [newIndex], as Media3 moves them. */
    fun moveRange(state: QueueState, view: QueueView, from: Int, to: Int, newIndex: Int): QueueState {
        var result = state
        val keys = view.keys.toMutableList()
        val count = to - from
        for (i in 0 until count) {
            val (source, target) = if (newIndex <= from) (from + i) to (newIndex + i) else from to (newIndex + count - 1)
            result = move(result, QueueView(keys.toList(), view.current), source, target)
            keys.add(target, keys.removeAt(source))
            renumberOpened(keys)
        }
        return result
    }

    /** Opened keys name their file's place in the opened files, which each step puts in row order again. */
    private fun renumberOpened(keys: MutableList<EntryKey>) {
        var next = 0
        for (i in keys.indices) if (keys[i] is EntryKey.Opened) keys[i] = EntryKey.Opened(next++)
    }

    /** The entry at [row] removed; null when that empties the queue. */
    fun remove(state: QueueState, view: QueueView, row: Int): QueueState? {
        val key = view.keys[row]
        val newKeys = view.keys.toMutableList().apply { removeAt(row) }
        // An empty queue is no queue: the caller forgets it (docs/playback.md §9.4).
        if (newKeys.isEmpty()) return null
        val openItems = openRows(state.openItems, newKeys)
        if (key is EntryKey.Opened) return state.copy(openItems = openItems)
        val edits = state.edits
        return state.copy(
            openItems = openItems,
            edits = edits.copy(journal = edits.journal + QueueEdit.Remove(key, laneRow(view.keys, row)), upNext = edits.upNext.filter { it != key }),
        )
    }

    /** The entry at [from] moved in the list so that it ends at [to]. */
    fun move(state: QueueState, view: QueueView, from: Int, to: Int): QueueState {
        if (from == to) return state
        val key = view.keys[from]
        val newKeys = view.keys.toMutableList().apply { add(to, removeAt(from)) }
        val openItems = openRows(state.openItems, newKeys)
        if (key is EntryKey.Opened) return state.copy(openItems = openItems)
        val edits = state.edits
        val move = QueueEdit.Move(key, anchorBefore(newKeys, to), laneRow(newKeys, to))
        return state.copy(openItems = openItems, edits = edits.copy(journal = edits.journal + move))
    }

    /** The up-next run without the entries at or before the current one in the play order. */
    fun played(state: QueueState, view: QueueView): QueueState {
        val upNext = state.edits.upNext
        if (upNext.isEmpty()) return state
        val wanted = upNext.toHashSet<EntryKey>()
        val rows = HashMap<EntryKey, Int>()
        view.keys.forEachIndexed { row, key -> if (key in wanted) rows[key] = row }
        val positions = view.order?.let { order -> IntArray(order.size).also { order.forEachIndexed { position, row -> it[row] = position } } }
        fun position(row: Int) = positions?.get(row) ?: row
        val current = position(view.current)
        val kept = upNext.filter { key -> rows[key]?.let { position(it) > current } == true }
        return if (kept.size == upNext.size) state else state.copy(edits = state.edits.copy(upNext = kept))
    }

    /** The rows of the up-next run right after the current entry in the list, in order: they stay next when shuffle goes on. */
    fun upNextRows(state: QueueState, view: QueueView): IntArray {
        val count = listRun(view, state.edits.upNext.toHashSet())
        return IntArray(count) { view.current + 1 + it }
    }

    /** The play position after which other songs that join the queue go: after the current entry and its up-next run. */
    fun joinAfter(state: QueueState, view: QueueView): Int {
        val upNext = state.edits.upNext.toHashSet<EntryKey>()
        val order = view.order ?: return view.current + listRun(view, upNext)
        val current = order.indexOf(view.current)
        return current + playRun(view.keys, order, current, upNext)
    }

    /** How many entries of the run follow the current one in the list. */
    private fun listRun(view: QueueView, upNext: Set<EntryKey>): Int {
        var row = view.current + 1
        while (row < view.keys.size && view.keys[row] in upNext) row++
        return row - view.current - 1
    }

    /** How many entries of the run follow play [current] in [order]. */
    private fun playRun(keys: List<EntryKey>, order: IntArray, current: Int, upNext: Set<EntryKey>): Int {
        var position = current + 1
        while (position < order.size && keys[order[position]] in upNext) position++
        return position - current - 1
    }

    /** The entry before [row] that the journal can name (not an opened file), or null when there is none. */
    private fun anchorBefore(keys: List<EntryKey>, row: Int): EntryKey? =
        (row - 1 downTo 0).firstNotNullOfOrNull { keys[it].takeUnless { key -> key is EntryKey.Opened } }

    /** [row] among the entries the journal counts, which leave out the opened files. */
    private fun laneRow(keys: List<EntryKey>, row: Int): Int = (0 until row).count { keys[it] !is EntryKey.Opened }

    /** The opened files of [keys] (by their place in [openItems]) at the rows they have there, in row order. */
    private fun openRows(openItems: List<SavedOpenItem>, keys: List<EntryKey>): List<SavedOpenItem> =
        keys.mapIndexedNotNull { row, key -> (key as? EntryKey.Opened)?.let { openItems[it.index].copy(index = row) } }
}

/** The restored queue from the compact request's list, the queue file and the opened files (docs/playback.md §9.4). */
object QueueRebuild {

    /**
     * The entries of the restored queue other than opened files: [library] with the journal of [edits] applied, added
     * entries as [addedSongs] has them (by number; those it lacks are left out). Added entries that no edit inserted
     * (a queue of added songs only, a folded one) come first, as the list the journal applies to. Without [songKeys] the
     * edits of library songs are left out (another library ID).
     */
    fun entries(library: List<Song>, edits: QueueEdits, addedSongs: Map<Long, Song>, songKeys: Boolean): List<QueueSlot> {
        val inserted = edits.journal.flatMapTo(HashSet()) { (it as? QueueEdit.Insert)?.entries.orEmpty() }
        val base = edits.added.map { EntryKey.Added(it.number) }.filterNot { it in inserted } + library.map { EntryKey.Song(it.id) }
        val songs = library.associateBy { it.id }
        return QueueJournal.replay(base, edits.journal, songKeys).mapNotNull { key ->
            when (key) {
                is EntryKey.Song -> songs[key.fileId]?.let { QueueSlot.Library(it) }
                is EntryKey.Added -> addedSongs[key.number]?.let { QueueSlot.Added(key.number, it) }
                is EntryKey.Opened -> null
            }
        }
    }

    /**
     * [slots] and [edits] folded (docs/playback.md §4.8): every entry but the opened files becomes an added entry with the
     * track [refOf] gives its file now, in order, the journal starts again, and the up-next run keeps its entries under
     * their new keys. The library's reference wins, since a saved one may name a file of another library ID; without it
     * an added entry keeps its saved one, and any other entry one of its file ID alone.
     */
    /** Whether a restore folds [edits]: more than 200 edits, or file IDs of another library ([songKeys] false). */
    fun mustFold(edits: QueueEdits, songKeys: Boolean): Boolean =
        !edits.isEmpty && (edits.journal.size > QueueJournal.MAX_EDITS || !songKeys)

    fun fold(slots: List<QueueSlot>, edits: QueueEdits, refOf: (Long) -> TrackRef?): Pair<List<QueueSlot>, QueueEdits> {
        var number = edits.nextNumber
        val tracks = edits.added.associate { it.number to it.track }
        val added = ArrayList<AddedEntry>()
        val renamed = HashMap<EntryKey, EntryKey.Added>()
        val folded = slots.map { slot ->
            val (old, song, track) = when (slot) {
                is QueueSlot.Open -> return@map slot
                is QueueSlot.Library -> Triple(EntryKey.Song(slot.song.id), slot.song, null)
                is QueueSlot.Added -> Triple(EntryKey.Added(slot.number), slot.song, tracks[slot.number])
            }
            val key = EntryKey.Added(number++)
            added += AddedEntry(key.number, refOf(song.id) ?: track ?: TrackRef(song.id, null, null, null, null))
            renamed[old] = key
            QueueSlot.Added(key.number, song)
        }
        return folded to QueueEdits(added = added, upNext = edits.upNext.mapNotNull { renamed[it] }, nextNumber = number)
    }
}
