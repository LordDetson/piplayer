package io.github.lorddetson.piplayer.playback

/**
 * What is saved between process lifetimes (docs/playback.md §4.1): the compact queue request rather than thousands of
 * URIs, the current song by its media index ID and its row, and what the system needs to show the song before the
 * library is read again.
 */
data class PlaybackSnapshot(
    val queue: QueueRequest,
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
) {

    init {
        require(songId >= 0) { "songId must not be negative" }
        require(row >= 0) { "row must not be negative" }
        require(positionMs >= 0) { "positionMs must not be negative" }
        require(repeatMode in REPEAT_OFF..REPEAT_ALL) { "Unknown repeat mode $repeatMode" }
        require(durationMs >= 0) { "durationMs must not be negative" }
    }

    companion object {
        /** Bumped when the saved keys or their meaning change; older files are then ignored. */
        const val SCHEMA_VERSION = 1

        /** The values of [androidx.media3.common.Player.getRepeatMode]. */
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }
}
