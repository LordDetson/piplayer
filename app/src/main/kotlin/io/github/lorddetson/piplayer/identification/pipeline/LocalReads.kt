package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.util.Locale

/** What the library database holds of a file for stage 0: its row, its track if the tag pass made one, and its [uri]. */
data class StoredFile(val row: AudioFileEntity, val track: TrackEntity?, val uri: String)

/**
 * Reads of the library database for stage 0; there is no write, since stage 0 stores no tag values, key or length there
 * (TI §3 "One run"). Only the failed-read count of a read through `:tagger` changes, by that read's own guard.
 */
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

    /**
     * Stage 0 of the library file [fileId]; null when it is gone or cannot be read. A stored key counts only once the tag
     * pass has read the file: the sync clears `tag_pass_at` of a changed file, whose stored key may be stale.
     */
    suspend fun library(fileId: Long): LocalFile? {
        val (row, track, uri) = library.file(fileId) ?: return null
        val folder = FolderKey(row.volumeName, row.relativePath).key
        val key = row.contentKey?.let(::plainKey)
        if (key != null && row.tagPassAt != null) {
            return LocalFile(
                target = IdentificationTarget.LibraryFile(fileId, key),
                fileName = row.displayName,
                tags = track?.toTags(),
                duration = stored(row),
                format = row.format,
                folder = folder,
                uri = uri,
            )
        }
        val read = tagger.read(uri, row.displayName, fileId) ?: return null
        return local(read, uri, row.displayName, folder) { readKey, _ -> IdentificationTarget.LibraryFile(fileId, readKey) }
    }

    /**
     * Stage 0 of the file at [uri] opened from another app, of which its provider says [provided]; [fallbackName] is
     * its name for the file-name guess when the provider gives none.
     */
    suspend fun opened(uri: String, provided: ProvidedFile, fallbackName: String = ""): LocalFile? {
        val read = tagger.read(uri, provided.name, fileId = null) ?: return null
        return local(read, uri, provided.name ?: fallbackName, folder = null) { key, size ->
            IdentificationTarget.OpenedFile(uri, key, provided.name, provided.size ?: size)
        }
    }

    /** What [read] tells of the file; null without a content key. [target] makes the target from the key and size. */
    private suspend fun local(
        read: TaggerRead,
        uri: String,
        fileName: String,
        folder: String?,
        target: (key: String, size: Long) -> IdentificationTarget,
    ): LocalFile? {
        val report = read.report
        val content = report.content ?: return null
        val key = plainKey(content.key) ?: return null
        val tags = if (report.error == null) {
            TrackTags.from(LegacyText.decode(report.properties, cyrillicLanguage(), legacyEncoding()).properties)
        } else {
            null
        }
        val plan = content.duration
        val duration = if (plan.check == DurationCheck.NONE) {
            plan.headerMs?.takeIf { ms -> ms > 0 }?.let(StageDuration::Exact) ?: StageDuration.Unknown
        } else {
            StageDuration.Pending(plan)
        }
        return LocalFile(
            target = target(key, read.size.coerceAtLeast(0)),
            fileName = fileName,
            tags = tags,
            duration = duration,
            format = report.container,
            folder = folder,
            uri = uri,
        )
    }

    companion object {

        /** The length the tag pass stored, or what is left to do for it (TI §3.1). */
        fun stored(row: AudioFileEntity): StageDuration = when (row.durationSource) {
            DurationSource.PENDING_WALK -> StageDuration.Pending(DurationPlan(null, DurationCheck.WALK))
            DurationSource.PENDING_FLAC_END -> StageDuration.Pending(DurationPlan(row.durationMs, DurationCheck.FLAC_END))
            DurationSource.UNKNOWN -> StageDuration.Unknown
            else -> row.durationMs?.takeIf { it > 0 }?.let(StageDuration::Exact) ?: StageDuration.Unknown
        }

        /** A content key as the library stores it: 64 lower-case hex digits; null for anything else. */
        private fun plainKey(key: String): String? =
            key.lowercase(Locale.ROOT).takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}
