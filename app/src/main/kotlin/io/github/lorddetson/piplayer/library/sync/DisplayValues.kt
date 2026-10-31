package io.github.lorddetson.piplayer.library.sync

import android.provider.MediaStore
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.search.SearchText

/** What lists show and sort a file by, before the tag pass has read it (docs/library.md §1.2). */
data class DisplayValues(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val titleKey: String,
    val searchText: String,
) {
    companion object {
        /** The values of an index row. */
        fun of(row: IndexRow): DisplayValues {
            val fileName = row.displayName.substringBeforeLast('.').ifEmpty { row.displayName }
            val title = row.title.present() ?: fileName
            val artist = row.artist.present()?.takeUnless { it == MediaStore.UNKNOWN_STRING }
            val album = row.album.present()
            return DisplayValues(
                title = title,
                artist = artist,
                album = album,
                durationMs = row.durationMs?.takeIf { it > 0 },
                titleKey = TextNormalizer.normalize(title),
                searchText = SearchText.of(title, artist, album, row.composer.present(), row.genre.present(), fileName),
            )
        }

        private fun String?.present(): String? = this?.takeUnless { it.isBlank() }
    }
}
