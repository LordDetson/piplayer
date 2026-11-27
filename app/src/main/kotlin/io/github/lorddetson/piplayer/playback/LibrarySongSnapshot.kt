package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.Song

internal fun snapshotSongId(id: String): Long? =
    id.toLongOrNull()?.takeIf { it > 0 && it.toString() == id }

/** Resolved songs and replay references, with the number of songs each original input contributed. */
internal class ResolvedSongs(
    val songs: List<Song>,
    val tracks: List<TrackRef>,
    val libraryId: String?,
    private val counts: List<Int>,
) {
    /** An input stands for its first song. Skip an unavailable row or use the last song when none follow it. */
    fun startIndex(originalIndex: Int): Int? =
        if (originalIndex in counts.indices) counts.subList(0, originalIndex).sum().coerceAtMost(songs.lastIndex) else null
}

/** Batch lookups return maps by file ID. The input rows still determine order, duplicates and the start index. */
internal fun resolveSongSnapshot(
    ids: List<Long>,
    songsById: (List<Long>) -> Map<Long, Song>,
    libraryId: () -> String?,
    tracksOf: (List<Song>) -> List<TrackRef>,
): ResolvedSongs {
    val found = songsById(ids)
    val songs = ids.mapNotNull(found::get)
    if (songs.isEmpty()) throw UnsupportedOperationException("The items name no song of the library")
    val library = libraryId()
    val tracks = tracksOf(songs)
    val counts = ids.map { if (it in found) 1 else 0 }
    return ResolvedSongs(songs, tracks, library, counts)
}
