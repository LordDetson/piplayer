package io.github.lorddetson.piplayer.playback

import kotlinx.serialization.Serializable

/** One file opened from another app, as it joins a queue and as it is saved (docs/playback.md §5). */
@Serializable
data class OpenedFile(
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
) {

    init {
        require(uri.isNotBlank()) { "uri must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(durationMs >= 0) { "durationMs must not be negative" }
    }
}

/** An [OpenedFile] at [index] of the queue it was saved in. */
@Serializable
data class SavedOpenItem(val index: Int, val file: OpenedFile) {

    init {
        require(index >= 0) { "index must not be negative" }
    }

    val uri: String get() = file.uri
}

/**
 * The queue a snapshot describes: either the compact library request, the small list of opened files, or both. Opened
 * files carry their final rows, because they sit inside the library list (docs/playback.md §5.3).
 */
data class QueueState(
    val library: QueueRequest?,
    val openItems: List<SavedOpenItem>,
    /** The library ID the songs' file IDs were read under (docs/library.md §8.4); null without library songs. */
    val libraryId: String? = null,
    /** The edits of the queue (docs/playback.md §9.4). */
    val edits: QueueEdits = QueueEdits(),
) {

    init {
        require(library != null || openItems.isNotEmpty() || edits.added.isNotEmpty()) {
            "A queue needs a library request, opened files or added songs"
        }
        require(openItems.zipWithNext().all { (first, second) -> first.index < second.index }) {
            "Opened files must have increasing rows"
        }
        if (library == null && edits.added.isEmpty()) {
            require(openItems.withIndex().all { (row, item) -> item.index == row }) {
                "A queue of opened files starts at row zero and has no gaps"
            }
        }
    }

    /**
     * The state after [files] are inserted right after [currentIndex] (docs/playback.md §5.1). An empty player starts a
     * queue of just the opened files.
     */
    fun withInserted(currentIndex: Int, files: List<OpenedFile>): QueueState {
        if (files.isEmpty()) return this
        if (currentIndex < 0) return QueueState(null, files.mapIndexed { row, file -> SavedOpenItem(row, file) }, libraryId)
        val inserted = files.mapIndexed { offset, file -> SavedOpenItem(currentIndex + 1 + offset, file) }
        val shifted = openItems.map { item ->
            if (item.index > currentIndex) item.copy(index = item.index + files.size) else item
        }
        return copy(openItems = (shifted + inserted).sortedBy { it.index })
    }
}

/**
 * What the user changed in a queue (docs/playback.md §9.4): the entries added, the journal of edits over the compact
 * request's list, the up-next run's entries and the number the next added entry gets.
 */
data class QueueEdits(
    val added: List<AddedEntry> = emptyList(),
    val journal: List<QueueEdit> = emptyList(),
    val upNext: List<EntryKey.Added> = emptyList(),
    val nextNumber: Long = 1,
) {
    /** Whether there is anything the queue file must keep. */
    val isEmpty: Boolean get() = added.isEmpty() && journal.isEmpty() && upNext.isEmpty()
}
