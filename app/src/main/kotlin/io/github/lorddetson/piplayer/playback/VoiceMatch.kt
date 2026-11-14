package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.lists.AlbumGroup
import io.github.lorddetson.piplayer.library.lists.ArtistGroup
import io.github.lorddetson.piplayer.library.lists.GenreGroup
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.songs.Song

/** What a voice request plays (docs/playback.md §10.3). */
sealed interface VoiceMatch {
    /** Every song: the query was empty. */
    data object AllSongs : VoiceMatch

    data class Artist(val key: String) : VoiceMatch

    data class Album(val key: String) : VoiceMatch

    /** The Songs list filtered by the query, from the song of [fileId]. */
    data class FoundSongs(val fileId: Long) : VoiceMatch

    /** A user's playlist by its ID (M2b). */
    data class Playlist(val id: Long) : VoiceMatch

    /** One of the smart lists of the tree (M2b, docs/library.md §17.3). */
    data class SmartList(val kind: ListKind) : VoiceMatch

    /** A genre by its genre key (M2b, docs/library.md §19.4). */
    data class Genre(val key: String) : VoiceMatch

    /** A playlist or a smart list as voice may name it: its [name] and what it plays. */
    data class NamedList(val name: String, val match: VoiceMatch)

    companion object {
        /**
         * The best match of [query] among what the app's search finds for it: [artists], [albums] and [songs], each in
         * its list's order, then [playlists] (the user's that hold an available song first, then the non-empty smart
         * lists by their names in the app's language) and [genres] (docs/playback.md §10.3): an artist, an album, a song
         * titled as the query, a playlist, a genre named as it, then the first song found, the first artist and the first
         * album. Null when nothing matches.
         */
        fun choose(
            query: String,
            artists: List<ArtistGroup>,
            albums: List<AlbumGroup>,
            songs: List<Song>,
            playlists: List<NamedList> = emptyList(),
            genres: List<GenreGroup> = emptyList(),
        ): VoiceMatch? {
            val wanted = TextNormalizer.normalize(query)
            if (wanted.isEmpty()) return AllSongs
            fun named(text: String) = TextNormalizer.normalize(text) == wanted
            artists.firstOrNull { named(it.name) }?.let { return Artist(it.key) }
            albums.firstOrNull { named(it.title) }?.let { return Album(it.key) }
            songs.firstOrNull { named(it.title) }?.let { return FoundSongs(it.id) }
            // A list the query names, which the songs its words find would otherwise hide (M2b, PIM-64).
            playlists.firstOrNull { named(it.name) }?.let { return it.match }
            genres.firstOrNull { !it.unknown && named(it.name) }?.let { return Genre(it.key) }
            if (songs.isNotEmpty()) return FoundSongs(songs.first().id)
            artists.firstOrNull()?.let { return Artist(it.key) }
            albums.firstOrNull()?.let { return Album(it.key) }
            return null
        }
    }
}
