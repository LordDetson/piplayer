package io.github.lorddetson.piplayer.library.sync

/** The kinds the media index gives an audio row (docs/library.md §1.2); [recording] is null where it has no such flag. */
data class MediaKinds(
    val music: Boolean = true,
    val podcast: Boolean = false,
    val audiobook: Boolean = false,
    val ringtone: Boolean = false,
    val notification: Boolean = false,
    val alarm: Boolean = false,
    val recording: Boolean? = false,
)

/**
 * One audio row of the media index as the sync reads it (docs/library.md §1.3): where the file is, what the index knows
 * about it, and the index generation of its last change. Text values come as the index gives them.
 */
data class IndexRow(
    val mediaStoreId: Long,
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val size: Long,
    val modifiedSeconds: Long,
    val generationModified: Long,
    val mimeType: String?,
    val kinds: MediaKinds,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
)
