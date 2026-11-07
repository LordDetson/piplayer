package io.github.lorddetson.piplayer.library.songs

/**
 * A song of the list (docs/playback.md §2.1, docs/library.md §1.4, §8.4). [id] is the file ID of PiPlayer, which also
 * names the song in queue requests and media IDs; [uri] is what the player opens, [artist] null when unknown,
 * [durationMs] null when the index could not read the file, [mediaStoreId] the media index's ID when it is known.
 */
data class Song(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val mediaStoreId: Long? = null,
    /** The disc number, for the disc headers of an album page (docs/library.md §6.1). */
    val discNumber: Int? = null,
    /** The section of the scroll thumb's bubble at this row, by the list's order (docs/library.md §11.2). */
    val section: String? = null,
)

/** Where the songs of the list come from: the library, or a test's own list. */
fun interface SongSource {
    /** Every song, in list order. */
    fun songs(): List<Song>
}
