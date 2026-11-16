package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.tagging.TagReport

/** What the library database holds of a file for stage 0: its row, its track if the tag pass made one, and its [uri]. */
data class StoredFile(val row: AudioFileEntity, val track: TrackEntity?, val uri: String)

/** Reads of the library database for stage 0; there is no write, since stage 0 writes nothing there (TI §3 "One run"). */
fun interface StoredFiles {
    suspend fun file(fileId: Long): StoredFile?
}

/** A reply of `:tagger` for stage 0: the report and the size of the file that was open. */
data class TaggerRead(val report: TagReport, val size: Long)

/** Reads through `:tagger` for stage 0. */
fun interface TaggerReads {

    /**
     * Opens [uri] and reads it through `:tagger` with the legacy marks, counted against the library file [fileId] or,
     * without one, against the file outside the library; null when it cannot be opened or `:tagger` gives no report.
     */
    suspend fun read(uri: String, name: String?, fileId: Long?): TaggerRead?
}

/** What a provider says of a file opened from another app. */
data class ProvidedFile(val name: String?, val size: Long?)

/**
 * Stage 0's reads (TI §3.1, §3 "One run") apart from Android: a library file that the tag pass has read comes from the
 * library database through [library]; a library file it has not read and a file opened from another app are read
 * through `:tagger` by [tagger], their content key taken from that read and their tags decoded with the legacy-encoding
 * setting. Nothing is written: the tag pass keeps the library's own values.
 */
class LocalReads(
    private val library: StoredFiles,
    private val tagger: TaggerReads,
    private val cyrillicLanguage: () -> Boolean,
    private val legacyEncoding: suspend () -> LegacyEncoding?,
) {

    /** Stage 0 of the library file [fileId]; null when it is gone or cannot be read. */
    suspend fun library(fileId: Long): LocalFile? = null

    /** Stage 0 of the file at [uri] opened from another app, of which its provider says [provided]. */
    suspend fun opened(uri: String, provided: ProvidedFile): LocalFile? = null
}
