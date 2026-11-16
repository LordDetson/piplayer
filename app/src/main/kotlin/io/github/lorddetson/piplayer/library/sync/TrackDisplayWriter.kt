package io.github.lorddetson.piplayer.library.sync

import androidx.room3.PooledConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.IndexInputRow
import io.github.lorddetson.piplayer.library.db.SqlRows
import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.ReadFileRow
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.db.replaceInChunks
import io.github.lorddetson.piplayer.library.sort.Collation
import io.github.lorddetson.piplayer.library.sort.SortKeys
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.FieldOverrides
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.ResolvingCores
import io.github.lorddetson.piplayer.library.values.TextForms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
    /** The tag pass tried the file and could not read it (`tag_failure`); counts only while it is unread. */
    private val failed: Boolean = false,
    /** The decided values of the file from `decided_mirror` (TI §7.1, §10.1). */
    private val decided: FieldOverrides = FieldOverrides.NONE,
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
        overrides = decided,
        volume = volume,
        relativePath = relativePath,
        forms = forms,
        failed = read == null && failed,
    )
}

/**
 * The only writer of `track_display` (TI §7.1): the sync, the tag pass and a new [VERSION] all come through here, so
 * that the merged values, the search columns, the reasons and the badge are always derived the same way. Every write
 * joins the decided values of `decided_mirror`, so none of these writers loses them (TI §10.1, §10.3).
 */
class TrackDisplayWriter(private val database: LibraryDatabase) {

    private val dao: LibraryDao = database.library()

    /** Derives the display of [fileIds] again from what the database holds. */
    suspend fun refresh(fileIds: List<Long>) {
        val rows = inputsOf(fileIds)
        val resolved = resolve(rows)
        database.withWriteTransaction { write(rows.map { it.fileId }, resolved) }
    }

    /**
     * The inputs of [fileIds], with their [decided] values when the caller has them at hand (the decision mirror, which
     * writes them), or else as `decided_mirror` holds them.
     */
    internal suspend fun inputsOf(fileIds: List<Long>, decided: Map<Long, FieldOverrides>? = null): List<DisplayInput> =
        fileIds.chunked(CHUNK).flatMap { chunk ->
            val reads = dao.readFilesOf(chunk).associateBy { it.fileId }
            val values = decided ?: overridesOf(database.decided().valuesOf(chunk))
            dao.indexInputsOf(chunk).map { it.toInput(reads[it.fileId], values[it.fileId]) }
        }

    /** The inputs of every file. */
    internal suspend fun allInputs(): List<DisplayInput> {
        val reads = dao.readFiles().associateBy { it.fileId }
        val decided = overridesOf(database.decided().all())
        return dao.indexInputs().map { it.toInput(reads[it.fileId], decided[it.fileId]) }
    }

    private fun IndexInputRow.toInput(read: ReadFileRow?, decided: FieldOverrides?) = DisplayInput(
        fileId,
        displayName,
        IndexValues(title, artist, album, composer, genre, durationMs, albumArtist, year, trackNumber),
        read,
        exactDurationMs,
        volume = volume,
        relativePath = relativePath,
        failed = failed,
        decided = decided ?: FieldOverrides.NONE,
    )

    companion object {
        /** A change of the rules that derive the display values raises this; the next sync derives them all again. */
        const val VERSION = 2

        private const val CHUNK = 500
        private const val DELETE_CHUNK = 2_000

        /** Above this many files the decided values of all are read at once, rather than in chunks. */
        private const val READ_ALL_DECIDED = 5_000

        /** The decided values of `decided_mirror` [rows] by file; a field this build does not know is left out. */
        fun overridesOf(rows: List<DecidedMirrorEntity>): Map<Long, FieldOverrides> = rows.groupBy { it.fileId }.mapValues { (_, own) ->
            FieldOverrides(own.mapNotNull { row -> DecidedField.ofName(row.field)?.let { it to Decided(row.value, row.level, row.decisionId) } }.toMap())
        }

        /** The decided values of [fileIds], as the sync joins them for the files it stores again (TI §10.3). */
        internal suspend fun decidedOf(database: LibraryDatabase, fileIds: List<Long>): Map<Long, FieldOverrides> =
            if (fileIds.size > READ_ALL_DECIDED) {
                overridesOf(database.decided().all())
            } else {
                fileIds.chunked(CHUNK).fold(HashMap()) { all, chunk -> all.apply { putAll(overridesOf(database.decided().valuesOf(chunk))) } }
            }

        /**
         * All cores but one ([ResolvingCores]), which stays for the thread that writes the database and the one that builds
         * what it writes next, so that neither waits behind a chunk being resolved.
         */
        private val resolvers = ResolvingCores.dispatcher

        /** Files per chunk of [resolving] and [write]. */
        internal const val CHUNK_SIZE = 2_000

        private val COLUMNS = listOf(
            "file_id", "title", "artist", "album", "duration_ms", "search_text", "missing_info_reasons",
            "album_key", "album_artist", "compilation", "year", "disc_number", "track_number", "artist_key",
            "album_search", "title_sort", "artist_sort", "album_sort", "album_artist_sort", "file_sort", "badge_state",
        )
        private val ARTIST_COLUMNS = listOf("file_id", "position", "artist_key", "name", "search_text", "sort_key")
        private val GENRE_COLUMNS = listOf("file_id", "position", "genre_key", "name", "search_text", "sort_key")

        /**
         * Normalizing 20 000 titles and search texts takes about a second on one core, so the cores share it. Each chunk
         * shares the forms and sort keys of its texts, so an artist or an album of many songs costs one of each, and
         * makes its keys with a collator of its own; all use [collation], which the caller stores with them (docs/
         * library.md §11.1).
         */
        internal suspend fun resolve(inputs: List<DisplayInput>, collation: Collation = SortKeys.current()): List<DisplayValues> =
            coroutineScope { resolving(inputs, collation).awaitAll().flatten() }

        /**
         * [resolve], started in [this] scope: one result per [CHUNK_SIZE] inputs, in order, each ready as soon as its
         * chunk is, so that a writer can store the first chunks while the cores resolve the others.
         */
        internal fun CoroutineScope.resolving(inputs: List<DisplayInput>, collation: Collation): List<Deferred<List<DisplayValues>>> =
            inputs.chunked(CHUNK_SIZE).map { chunk ->
                async(resolvers) {
                    val forms = TextForms(keys = collation.newKeys())
                    chunk.map { it.resolve(forms) }
                }
            }

        /** Writes [values] for [fileIds], in the same order. */
        internal suspend fun PooledConnection.write(fileIds: List<Long>, values: List<DisplayValues>) =
            writeChunks(fileIds, values.chunked(CHUNK_SIZE).map { CompletableDeferred(it) })

        /** Writes the values of [fileIds] as [resolving] gives them, [CHUNK_SIZE] files per chunk. */
        internal suspend fun PooledConnection.writeChunks(fileIds: List<Long>, chunks: List<Deferred<List<DisplayValues>>>) {
            val idChunks = fileIds.chunked(CHUNK_SIZE)
            check(idChunks.size == chunks.size) { "${chunks.size} chunks of values for ${idChunks.size} chunks of files" }
            suspend fun rows(c: Int) = idChunks[c].zip(chunks[c].await())
            replaceInChunks("track_display", COLUMNS, idChunks.indices.map { c -> suspend { rows(c) } }) { (id, values) ->
                display(id, values)
            }
            // A file may now have fewer artists and genres than before.
            for (table in listOf("display_artist", "display_genre")) {
                for (chunk in fileIds.chunked(DELETE_CHUNK)) {
                    usePrepared("DELETE FROM $table WHERE file_id IN (SELECT value FROM json_each(?))") { statement ->
                        statement.bindText(1, chunk.joinToString(",", "[", "]"))
                        statement.step()
                    }
                }
            }
            // Resolved with the other values, off this transaction; all of them are by now.
            val artists = idChunks.indices.map { c ->
                suspend {
                    rows(c).flatMap { (fileId, values) ->
                        values.displayArtists.mapIndexed { position, artist -> Triple(fileId, position, artist) }
                    }
                }
            }
            replaceInChunks("display_artist", ARTIST_COLUMNS, artists) { (fileId, position, artist) ->
                long(fileId)
                long(position.toLong())
                text(artist.key)
                text(artist.name)
                text(artist.searchText)
                blob(artist.sortKey.bytes)
            }
            // The genres lists group by (docs/library.md §19.1), in the same chunks.
            val genres = idChunks.indices.map { c ->
                suspend {
                    rows(c).flatMap { (fileId, values) ->
                        values.displayGenres.mapIndexed { position, genre -> Triple(fileId, position, genre) }
                    }
                }
            }
            replaceInChunks("display_genre", GENRE_COLUMNS, genres) { (fileId, position, genre) ->
                long(fileId)
                long(position.toLong())
                text(genre.key)
                text(genre.name)
                text(genre.searchText)
                blob(genre.sortKey.bytes)
            }
        }

        private fun SqlRows.Row.display(id: Long, values: DisplayValues) {
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
            long(values.badgeState.toLong())
        }
    }
}
