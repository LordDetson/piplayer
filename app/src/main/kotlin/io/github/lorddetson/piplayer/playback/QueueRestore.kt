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

    /**
     * Whether [start] resumes the saved item itself, so that its playback goes on (docs/playback.md §11.3): a song found
     * as [currentFileId], or the saved opened file, at the saved position.
     */
    fun resumesSavedItem(order: List<QueueSlot>, start: QueueStart, snapshot: PlaybackSnapshot, currentFileId: Long?): Boolean {
        val slot = order.getOrNull(start.index) ?: return false
        if (start.positionMs != snapshot.positionMs) return false
        return if (snapshot.songId < 0) {
            slot is QueueSlot.Open && slot.item.uri == snapshot.uri
        } else {
            currentFileId != null && slot.fileId == currentFileId
        }
    }

    /**
     * The media ID under which the saved playback of [snapshot] ends at a restore that does not resume it (§11.3), or
     * null: only a playback that counted, of a library song whose file [currentFileId] is known now, has an event whose
     * listened time is still to be written. One not counted ends without a trace, as any playback that does not count.
     */
    fun endedSavedPlayback(snapshot: PlaybackSnapshot, resumes: Boolean, currentFileId: Long?): String? {
        if (resumes || snapshot.listen?.counted != true || snapshot.songId < 0) return null
        return currentFileId?.toString()
    }

    /** The longest end of a song in which a saved position counts as its end (docs/playback.md §4.3.1). */
    const val MAX_END_ZONE_MS = 5_000L

    /**
     * The last part of a song of [durationMs] in which a saved position counts as its end (docs/playback.md §4.3.1): 2 %
     * of its length, at most [MAX_END_ZONE_MS].
     */
    fun endZoneMs(durationMs: Long): Long = minOf(MAX_END_ZONE_MS, durationMs.coerceAtLeast(0) / 50)

    /** Whether [positionMs] is at the end of a song of [durationMs]; never when the length is unknown (§4.3.1). */
    fun isAtEnd(positionMs: Long, durationMs: Long?): Boolean =
        durationMs != null && durationMs > 0 && positionMs >= durationMs - endZoneMs(durationMs)

    /**
     * The row that plays after [index] of [size] rows when its song ends (§4.3.1): the same row with repeat one, else
     * the next in [playOrder] (the rows in play order, or null for list order), the first after the last with repeat
     * all; null after the last with repeat off.
     */
    fun nextAfterEnd(index: Int, size: Int, repeatMode: Int, playOrder: IntArray?): Int? {
        if (index !in 0 until size) return null
        if (repeatMode == PlaybackSnapshot.REPEAT_ONE) return index
        val order = playOrder?.takeIf { it.size == size }
        val place = order?.indexOf(index) ?: index
        if (place < 0) return null
        val next = when {
            place + 1 < size -> place + 1
            repeatMode == PlaybackSnapshot.REPEAT_ALL -> 0
            else -> return null
        }
        return order?.get(next) ?: next
    }

    /**
     * How far past the queue item's length a saved position may be and still count as its end (§4.3.1): the player's
     * length of the same file differs from the library's by rounding and its estimate. Further past, the item's length
     * is too short for the file, and only the player's own length decides.
     */
    const val PAST_END_TOLERANCE_MS = 1_000L

    /**
     * [start], moved off the end of its song when its position is there (§4.3.1): to the start of the row that plays
     * next, or to the start of the same row when none does. The length is the queue item's; an unknown one, or one the
     * position is more than [PAST_END_TOLERANCE_MS] past, moves nothing and leaves the decision to the player's length.
     */
    fun offTheEnd(order: List<QueueSlot>, start: QueueStart, repeatMode: Int, playOrder: IntArray?): QueueStart {
        val slot = order.getOrNull(start.index) ?: return start
        val durationMs = slot.durationMs ?: return start
        if (start.positionMs > durationMs + PAST_END_TOLERANCE_MS) return start
        if (!isAtEnd(start.positionMs, durationMs)) return start
        return QueueStart(nextAfterEnd(start.index, order.size, repeatMode, playOrder) ?: start.index, 0)
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

/** The length of the entry before the player reads the file, null when it is not known (docs/playback.md §4.3.1). */
private val QueueSlot.durationMs: Long?
    get() = when (this) {
        is QueueSlot.Library -> song.durationMs
        is QueueSlot.Added -> song.durationMs
        is QueueSlot.Open -> item.file.durationMs
    }?.takeIf { it > 0 }
