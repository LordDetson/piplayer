package io.github.lorddetson.piplayer.library.songs

/**
 * A song of the list (docs/playback.md §2.1, docs/library.md §1.4). [id] is the media index's ID, [uri] what the player
 * opens, [artist] null when unknown, [durationMs] null when the index could not read the file.
 */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/** Where the songs of the list come from: the library, or a test's own list. */
fun interface SongSource {
    /** Every song, in list order. */
    fun songs(): List<Song>
}
