package io.github.lorddetson.piplayer.library.lists

/** Who an album is shown by (docs/library.md §6.2). */
sealed interface AlbumCredit {
    data class Named(val name: String) : AlbumCredit

    data object Various : AlbumCredit

    data object Unknown : AlbumCredit

    companion object {
        /** [artist] is an artist of its songs, null when none has one; [oneArtist] says all its songs share it. */
        fun of(albumArtist: String?, compilation: Boolean, artist: String?, oneArtist: Boolean): AlbumCredit = when {
            albumArtist != null -> Named(albumArtist)
            compilation -> Various
            artist == null -> Unknown
            oneArtist -> Named(artist)
            else -> Various
        }
    }
}
