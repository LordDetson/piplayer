package io.github.lorddetson.piplayer.library.songs

/**
 * A song of the temporary list (docs/playback.md §2.1). [id] is the media index's ID, [uri] what the player opens,
 * [artist] null when unknown, [durationMs] null when the index could not read the file.
 */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/** Where the songs of the temporary list come from: the media index, or a test's own list. */
fun interface SongSource {
    /** Every song, in no particular order. */
    fun songs(): List<Song>
}
