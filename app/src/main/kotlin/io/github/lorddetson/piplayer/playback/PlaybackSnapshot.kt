package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.TrackRef

/**
 * What is saved between process lifetimes (docs/playback.md §4.1, §5.3): the compact library request and the small list
 * of files opened from other apps at their rows, the current item by its media index ID or URI, and what the system
 * needs to show it before the library is read again.
 */
data class PlaybackSnapshot(
    val queue: QueueRequest?,
    val openItems: List<SavedOpenItem> = emptyList(),
    val songId: Long,
    val row: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val uri: String?,
    /** The current library song as a track reference (docs/playback.md §4.8); the store fills it when it writes. */
    val current: TrackRef? = null,
    /** The library ID the file IDs of this snapshot belong to (docs/library.md §8.4). */
    val libraryId: String? = null,
    /** The revision of the queue file this snapshot belongs to; 0 when there is none. */
    val queueRevision: Long = 0,
    /** Read from schema 1 or 2: [songId] and the request's start are media index IDs, not file IDs. */
    val mediaIndexIds: Boolean = false,
) {

    init {
        require(queue != null || openItems.isNotEmpty()) { "A snapshot needs a library request or opened files" }
        require(openItems.zipWithNext().all { (first, second) -> first.index < second.index }) {
            "Opened files must have increasing rows"
        }
        if (queue == null) {
            require(openItems.withIndex().all { (row, item) -> item.index == row }) {
                "A queue of opened files starts at row zero and has no gaps"
            }
        }
        require(songId >= -1) { "songId must not be less than -1" }
        if (songId == -1L) {
            require(openItems.any { it.index == row && it.uri == uri }) {
                "The current opened file must be in the saved queue at its row"
            }
        } else {
            require(queue != null) { "A library song needs a library request" }
        }
        require(row >= 0) { "row must not be negative" }
        require(positionMs >= 0) { "positionMs must not be negative" }
        require(repeatMode in REPEAT_OFF..REPEAT_ALL) { "Unknown repeat mode $repeatMode" }
        require(durationMs >= 0) { "durationMs must not be negative" }
    }

    companion object {
        /** Bumped when the saved keys or their meaning change; older files are migrated or ignored. */
        const val SCHEMA_VERSION = 3

        /** Schema 2, with opened files and media index IDs (M1 step 5). */
        const val OPEN_SCHEMA_VERSION = 2

        /** The library-only schema written before open-with (step 4). */
        const val LEGACY_SCHEMA_VERSION = 1

        /** The values of [androidx.media3.common.Player.getRepeatMode]. */
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }
}
