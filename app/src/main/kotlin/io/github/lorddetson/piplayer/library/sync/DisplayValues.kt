package io.github.lorddetson.piplayer.library.sync

import io.github.lorddetson.piplayer.library.lists.DisplayArtist
import io.github.lorddetson.piplayer.library.sort.SortKey
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
    val searchText: String,
    val reasons: Int? = null,
    /** What groups and sorts the file (docs/library.md §6.2). */
    val albumKey: String? = null,
    val albumArtist: String? = null,
    val compilation: Boolean = false,
    val year: Int? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val displayArtists: List<DisplayArtist> = emptyList(),
    val artistKey: String? = null,
    val albumSearch: String = "",
    /** The collator's keys (docs/library.md §11.1), from the sort tags once read. */
    val titleSort: SortKey = SortKey.EMPTY,
    val artistSort: SortKey? = null,
    val albumSort: SortKey? = null,
    val albumArtistSort: SortKey? = null,
    val fileSort: SortKey = SortKey.EMPTY,
) {
    /** The names of [displayArtists]. */
    val artists: List<String> get() = displayArtists.map { it.name }

    companion object {
        /** The values of a file only the index has spoken of. */
        fun of(index: IndexValues, displayName: String, volume: String = "", relativePath: String = ""): DisplayValues =
            MergedValues.of(displayName, index, tags = null, read = false, volume = volume, relativePath = relativePath)

        /** The values of an index row. */
        fun of(row: IndexRow): DisplayValues = of(
            IndexValues(row.title, row.artist, row.album, row.composer, row.genre, row.durationMs, row.albumArtist, row.year, row.trackNumber),
            row.displayName,
            row.volume,
            row.relativePath,
        )
    }
}
