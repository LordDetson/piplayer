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
) {

    init {
        require(library != null || openItems.isNotEmpty()) { "A queue needs a library request or opened files" }
        require(openItems.zipWithNext().all { (first, second) -> first.index < second.index }) {
            "Opened files must have increasing rows"
        }
        if (library == null) {
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
        if (currentIndex < 0) return QueueState(null, files.mapIndexed { row, file -> SavedOpenItem(row, file) })
        val inserted = files.mapIndexed { offset, file -> SavedOpenItem(currentIndex + 1 + offset, file) }
        val shifted = openItems.map { item ->
            if (item.index > currentIndex) item.copy(index = item.index + files.size) else item
        }
        return QueueState(library, (shifted + inserted).sortedBy { it.index })
    }
}
