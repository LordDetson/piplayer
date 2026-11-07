package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.songs.Song

/** Where a restored queue starts and how far into that song (docs/playback.md §4.3). */
data class QueueStart(val index: Int, val positionMs: Long)

/** What sits at a row of the restored queue: a library song or a file opened from another app. */
sealed interface QueueSlot {
    data class Library(val song: Song) : QueueSlot
    data class Open(val item: SavedOpenItem) : QueueSlot

    /** An entry an edit added, by its [number] (docs/playback.md §9.4). */
    data class Added(val number: Long, val song: Song) : QueueSlot
}

/** Which item of the restored queue resumes at which position (docs/playback.md §4.3, §5.3). */
object QueueRestore {

    /**
     * The saved queue in its final order: the library list with the opened files put back at their saved rows. A
     * changed library may move them a little; they keep their order and stay near where they were.
     */
    fun order(
        library: List<Song>,
        snapshot: PlaybackSnapshot,
        openItems: List<SavedOpenItem> = snapshot.openItems,
    ): List<QueueSlot> = withOpened(library.map { QueueSlot.Library(it) }, snapshot, openItems)

    /** [entries], the library songs and added entries in order, with the opened files put back at their saved rows. */
    fun withOpened(
        entries: List<QueueSlot>,
        snapshot: PlaybackSnapshot,
        openItems: List<SavedOpenItem> = snapshot.openItems,
    ): List<QueueSlot> {
        val library = entries
        val opens = openItems.sortedBy { it.index }
        if (library.isEmpty()) return opens.map { QueueSlot.Open(it) }
        val slots = arrayOfNulls<QueueSlot>(library.size + opens.size)
        for (open in opens) {
            var row = open.index.coerceIn(0, slots.lastIndex)
            while (row < slots.size && slots[row] != null) row++
            if (row >= slots.size) row = slots.indexOfFirst { it == null }
            if (row >= 0) slots[row] = QueueSlot.Open(open)
        }
        var libraryRow = 0
        for (row in slots.indices) {
            if (slots[row] == null && libraryRow < library.size) {
                slots[row] = library[libraryRow++]
            }
        }
        return slots.filterNotNull()
    }

    /**
     * The saved current item by its URI when it is an opened file, or by [currentFileId] when it is a song: the file the
     * saved song is now (docs/playback.md §4.8), null when it is gone. If it is gone, the saved row is used with position
     * zero: the position belonged to another file.
     */
    fun choose(
        order: List<QueueSlot>,
        snapshot: PlaybackSnapshot,
        currentFileId: Long? = snapshot.songId.takeIf { it >= 0 },
    ): QueueStart? {
        if (order.isEmpty()) return null
        val fallback = snapshot.row.coerceIn(0, order.lastIndex)
        if (snapshot.songId < 0) {
            val matches = order.mapIndexedNotNull { row, slot ->
                if (slot is QueueSlot.Open && slot.item.uri == snapshot.uri) row else null
            }
            val exact = matches.firstOrNull { it == fallback } ?: matches.firstOrNull()
            if (exact != null) return QueueStart(exact, snapshot.positionMs)
        } else if (currentFileId != null) {
            // A song in the queue twice (once added) resumes at its saved row when that is one of its places.
            val matches = order.mapIndexedNotNull { row, slot -> if (slot.fileId == currentFileId) row else null }
            val exact = matches.firstOrNull { it == fallback } ?: matches.firstOrNull()
            if (exact != null) return QueueStart(exact, snapshot.positionMs)
        }
        return QueueStart(fallback, 0)
    }

    /** The opened files of [order] at the rows they really got after the library was rebuilt. */
    fun openItems(order: List<QueueSlot>): List<SavedOpenItem> = order.mapIndexedNotNull { row, slot ->
        if (slot is QueueSlot.Open) slot.item.copy(index = row) else null
    }
}

/** The file a library song or an added entry plays; null for an opened file. */
private val QueueSlot.fileId: Long?
    get() = when (this) {
        is QueueSlot.Library -> song.id
        is QueueSlot.Added -> song.id
        is QueueSlot.Open -> null
    }
