package io.github.lorddetson.piplayer.library.sync

import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues

/**
 * What lists show and sort a file by (docs/library.md §1.2). [reasons] are the missing-information reasons of
 * [MergedValues], null until the tag pass has read the file.
 */
data class DisplayValues(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val titleKey: String,
    val searchText: String,
    val reasons: Int? = null,
) {
    companion object {
        /** The values of a file only the index has spoken of. */
        fun of(index: IndexValues, displayName: String): DisplayValues =
            MergedValues.of(displayName, index, tags = null, read = false)

        /** The values of an index row. */
        fun of(row: IndexRow): DisplayValues = of(
            IndexValues(row.title, row.artist, row.album, row.composer, row.genre, row.durationMs),
            row.displayName,
        )
    }
}
