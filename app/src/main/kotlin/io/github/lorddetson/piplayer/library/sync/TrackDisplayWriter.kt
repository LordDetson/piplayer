package io.github.lorddetson.piplayer.library.sync

import androidx.room3.PooledConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.IndexInputRow
import io.github.lorddetson.piplayer.library.db.JsonRows
import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.ReadFileRow
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.replaceAll
import io.github.lorddetson.piplayer.library.sort.Collation
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TextForms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** What the display of a file is resolved from; [read] is whether the tag pass has read it. */
internal class DisplayInput(
    val fileId: Long,
    val displayName: String,
    val index: IndexValues?,
    val read: ReadFileRow? = null,
    private val exactDurationMs: Long? = read?.exactDurationMs,
    private val volume: String = "",
    private val relativePath: String = "",
) {
    /** Normalizes, so not on a thread that playback or a list waits for; [forms] are shared by a batch. */
    fun resolve(forms: TextForms = TextForms()): DisplayValues = MergedValues.of(
        displayName,
        index,
        read?.let(TrackEntity::tagsOf),
        read = read != null,
        exactDurationMs = exactDurationMs,
        tagLengthMs = read?.tagLengthMs,
        embeddedCover = read?.embeddedCover == true,
        volume = volume,
        relativePath = relativePath,
        forms = forms,
    )
}

/**
 * The only writer of `track_display` (TI §7.1): the sync, the tag pass and a new [VERSION] all come through here, so
 * that the merged values, the search columns and the reasons are always derived the same way.
 */
class TrackDisplayWriter(private val database: LibraryDatabase) {

    private val dao: LibraryDao = database.library()

    /** Derives the display of [fileIds] again from what the database holds. */
    suspend fun refresh(fileIds: List<Long>) {
        val rows = inputsOf(fileIds)
        val resolved = resolve(rows)
        database.withWriteTransaction { write(rows.map { it.fileId }, resolved) }
    }

    /** The inputs of [fileIds]. */
    internal suspend fun inputsOf(fileIds: List<Long>): List<DisplayInput> = fileIds.chunked(CHUNK).flatMap { chunk ->
        val reads = dao.readFilesOf(chunk).associateBy { it.fileId }
        dao.indexInputsOf(chunk).map { it.toInput(reads[it.fileId]) }
    }

    /** The inputs of every file. */
    internal suspend fun allInputs(): List<DisplayInput> {
        val reads = dao.readFiles().associateBy { it.fileId }
        return dao.indexInputs().map { it.toInput(reads[it.fileId]) }
    }

    private fun IndexInputRow.toInput(read: ReadFileRow?) = DisplayInput(
        fileId,
        displayName,
        IndexValues(title, artist, album, composer, genre, durationMs, albumArtist, year, trackNumber),
        read,
        exactDurationMs,
        volume = volume,
        relativePath = relativePath,
    )

    companion object {
        /** A change of the rules that derive the display values raises this; the next sync derives them all again. */
        const val VERSION = 2

        private const val CHUNK = 500
        private const val DELETE_CHUNK = 2_000
        private const val RESOLVE_CHUNK = 2_000

        private val COLUMNS = listOf(
            "file_id", "title", "artist", "album", "duration_ms", "search_text", "missing_info_reasons",
            "album_key", "album_artist", "compilation", "year", "disc_number", "track_number", "artist_key",
            "album_search", "title_sort", "artist_sort", "album_sort", "album_artist_sort", "file_sort",
        )
        private val BLOBS = setOf("title_sort", "artist_sort", "album_sort", "album_artist_sort", "file_sort")
        private val ARTIST_COLUMNS = listOf("file_id", "position", "artist_key", "name", "search_text", "sort_key")

        /**
         * Normalizing 20 000 titles and search texts takes about a second on one core, so the cores share it. Each chunk
         * shares the forms and sort keys of its texts, so an artist or an album of many songs costs one of each, and
         * makes its keys with a collator of its own; all use [collation], which the caller stores with them (docs/
         * library.md §11.1).
         */
        internal suspend fun resolve(inputs: List<DisplayInput>, collation: Collation = SortKeys.current()): List<DisplayValues> =
            coroutineScope {
                inputs.chunked(RESOLVE_CHUNK)
                    .map { chunk ->
                        async(Dispatchers.Default) {
                            val forms = TextForms(keys = collation.newKeys())
                            chunk.map { it.resolve(forms) }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }

        /** Writes [values] for [fileIds], in the same order. */
        internal suspend fun PooledConnection.write(fileIds: List<Long>, values: List<DisplayValues>) {
            replaceAll("track_display", COLUMNS, fileIds.indices.toList(), blobs = BLOBS) { i -> display(fileIds[i], values[i]) }
            // A file may now have fewer artists than before.
            for (chunk in fileIds.chunked(DELETE_CHUNK)) {
                usePrepared("DELETE FROM display_artist WHERE file_id IN (SELECT value FROM json_each(?))") { statement ->
                    statement.bindText(1, chunk.joinToString(",", "[", "]"))
                    statement.step()
                }
            }
            // Resolved with the other values, off this transaction.
            val artists = fileIds.indices.flatMap { i ->
                values[i].displayArtists.mapIndexed { position, artist -> Triple(fileIds[i], position, artist) }
            }
            replaceAll("display_artist", ARTIST_COLUMNS, artists, blobs = setOf("sort_key")) { (fileId, position, artist) ->
                long(fileId)
                long(position.toLong())
                text(artist.key)
                text(artist.name)
                text(artist.searchText)
                blob(artist.sortKey.bytes)
            }
        }

        private fun JsonRows.Row.display(id: Long, values: DisplayValues) {
            long(id)
            text(values.title)
            text(values.artist)
            text(values.album)
            long(values.durationMs)
            text(values.searchText)
            long(values.reasons?.toLong())
            text(values.albumKey)
            text(values.albumArtist)
            flag(values.compilation)
            long(values.year?.toLong())
            long(values.discNumber?.toLong())
            long(values.trackNumber?.toLong())
            text(values.artistKey)
            text(values.albumSearch)
            blob(values.titleSort.bytes)
            blob(values.artistSort?.bytes)
            blob(values.albumSort?.bytes)
            blob(values.albumArtistSort?.bytes)
            blob(values.fileSort.bytes)
        }
    }
}
