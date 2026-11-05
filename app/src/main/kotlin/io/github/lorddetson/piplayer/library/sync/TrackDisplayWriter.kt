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
import io.github.lorddetson.piplayer.library.values.IndexValues
import io.github.lorddetson.piplayer.library.values.MergedValues
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
) {
    /** Normalizes, so not on a thread that playback or a list waits for. */
    fun resolve(): DisplayValues = MergedValues.of(
        displayName,
        index,
        read?.let(TrackEntity::tagsOf),
        read = read != null,
        exactDurationMs = read?.exactDurationMs,
        tagLengthMs = read?.tagLengthMs,
        embeddedCover = read?.embeddedCover == true,
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
        IndexValues(title, artist, album, composer, genre, durationMs),
        read,
    )

    companion object {
        /** A change of the rules that derive the display values raises this; the next sync derives them all again. */
        const val VERSION = 1

        private const val CHUNK = 500
        private const val RESOLVE_CHUNK = 2_000

        private val COLUMNS = listOf(
            "file_id", "title", "artist", "album", "duration_ms", "title_key", "search_text", "missing_info_reasons",
        )

        /** Normalizing 20 000 titles and search texts takes about a second on one core, so the cores share it. */
        internal suspend fun resolve(inputs: List<DisplayInput>): List<DisplayValues> = coroutineScope {
            inputs.chunked(RESOLVE_CHUNK)
                .map { chunk -> async(Dispatchers.Default) { chunk.map { it.resolve() } } }
                .awaitAll()
                .flatten()
        }

        /** Writes [values] for [fileIds], in the same order. */
        internal suspend fun PooledConnection.write(fileIds: List<Long>, values: List<DisplayValues>) {
            replaceAll("track_display", COLUMNS, fileIds.indices.toList()) { i -> display(fileIds[i], values[i]) }
        }

        private fun JsonRows.Row.display(id: Long, values: DisplayValues) {
            long(id)
            text(values.title)
            text(values.artist)
            text(values.album)
            long(values.durationMs)
            text(values.titleKey)
            text(values.searchText)
            long(values.reasons?.toLong())
        }
    }
}
