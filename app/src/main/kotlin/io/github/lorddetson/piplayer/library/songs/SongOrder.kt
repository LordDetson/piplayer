package io.github.lorddetson.piplayer.library.songs

import io.github.lorddetson.piplayer.identification.text.TextNormalizer

/** The order of the temporary list (docs/playback.md §2.1). */
object SongOrder {

    /**
     * By the title normalized as for search, compared character by character, then by ID: the list screen and the
     * playback service sort the same songs the same way.
     */
    fun byTitle(songs: List<Song>): List<Song> = songs
        .map { TextNormalizer.normalize(it.title) to it }
        .sortedWith(compareBy<Pair<String, Song>> { it.first }.thenBy { it.second.id })
        .map { it.second }
}
