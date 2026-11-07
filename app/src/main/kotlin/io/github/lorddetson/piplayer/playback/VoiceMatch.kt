package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.songs.Song

/** What a voice request plays (docs/playback.md §10.3). */
sealed interface VoiceMatch {
    /** Every song: the query was empty. */
    data object AllSongs : VoiceMatch

    data class Artist(val key: String) : VoiceMatch

    data class Album(val key: String) : VoiceMatch

    /** The Songs list filtered by the query, from the song of [fileId]. */
    data class FoundSongs(val fileId: Long) : VoiceMatch

    companion object {
        /**
         * The best match of [query] among what the app's search finds for it: [artists], [albums] and [songs], each in
         * its list's order. Null when nothing matches.
         */
        fun choose(query: String, artists: List<ArtistGroup>, albums: List<AlbumGroup>, songs: List<Song>): VoiceMatch? {
            val wanted = TextNormalizer.normalize(query)
            if (wanted.isEmpty()) return AllSongs
            fun named(text: String) = TextNormalizer.normalize(text) == wanted
            artists.firstOrNull { named(it.name) }?.let { return Artist(it.key) }
            albums.firstOrNull { named(it.title) }?.let { return Album(it.key) }
            if (songs.isNotEmpty()) return FoundSongs((songs.firstOrNull { named(it.title) } ?: songs.first()).id)
            artists.firstOrNull()?.let { return Artist(it.key) }
            albums.firstOrNull()?.let { return Album(it.key) }
            return null
        }
    }
}
