package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.identification.text.TextNormalizer

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

/** The sort keys of names (docs/library.md §6.3): the one place step 11 changes for locale-aware keys. */
object SortKeys {
    /** The key of [text]: normalized as for search, so that case, diacritics and ё do not change the order. */
    fun of(text: String): String = of(text, TextNormalizer.normalize(text))

    /** The key of [text] whose [normalized] form the caller has already. */
    fun of(text: String, normalized: String): String = normalized
}
