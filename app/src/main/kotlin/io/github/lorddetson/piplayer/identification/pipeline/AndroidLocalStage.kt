package io.github.lorddetson.piplayer.identification.pipeline

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.db.AudioFileEntity
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.tagging.outsideTarget
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Stage 0 in the app (TI §3.1, §3 "One run"): a library file that the tag pass has read comes from the library database
 * through [library]; a library file it has not read and a file opened from another app are read through `:tagger` by
 * [client], counted against the file like every read, and their tags decoded with the legacy-encoding setting. Walks
 * of the length run in `:tagger` too. Nothing is stored: the tag pass keeps the library's own values.
 */
class AndroidLocalStage(
    context: Context,
    private val client: TaggerClient,
    private val library: LibraryAccess,
    private val cyrillicLanguage: () -> Boolean = { systemCyrillic(context) },
    private val legacyEncoding: suspend () -> LegacyEncoding? = { LibrarySettings(context).readLegacyEncoding() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalStage {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(file: FileRef): LocalFile? = withContext(ioDispatcher) {
        when (file) {
            is FileRef.Library -> libraryFile(file.fileId)
            is FileRef.Opened -> {
                val uri = Uri.parse(file.uri)
                val provided = provided(uri)
                readThrough(
                    uri,
                    provided.name,
                    folder = null,
                    tagTarget = { descriptor -> outsideTarget(file.uri, descriptor) },
                    target = { key, size -> IdentificationTarget.OpenedFile(file.uri, key, provided.name, provided.size ?: size) },
                )
            }
        }
    }

    override suspend fun walk(file: LocalFile, plan: DurationPlan): Long? = withContext(ioDispatcher) {
        val descriptor = open(Uri.parse(file.uri)) ?: return@withContext null
        descriptor.use {
            val target = when (val identified = file.target) {
                is IdentificationTarget.LibraryFile -> TagTarget.LibraryFile(identified.fileId)
                is IdentificationTarget.OpenedFile -> outsideTarget(file.uri, it)
            }
            (client.walkDuration(target, it, plan) as? TaggerResult.Replied)?.value
        }
    }

    /** What the library holds of a file the tag pass has read; a file it has not read is read now. */
    private suspend fun libraryFile(fileId: Long): LocalFile? {
        val (row, track) = library.use { database ->
            runBlocking { database.library().file(fileId) to database.library().trackOf(fileId) }
        }
        if (row == null) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(row.volumeName), row.mediaStoreId)
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
                uri = uri.toString(),
            )
        }
        return readThrough(
            uri,
            row.displayName,
            folder,
            tagTarget = { TagTarget.LibraryFile(fileId) },
            target = { key, _ -> IdentificationTarget.LibraryFile(fileId, key) },
        )
    }

    /**
     * One read through `:tagger`, counted against [tagTarget]; null when the file cannot be opened or read, so it has no
     * content key. [target] makes the file's target from its content key and size.
     */
    private suspend fun readThrough(
        uri: Uri,
        name: String?,
        folder: String?,
        tagTarget: (ParcelFileDescriptor) -> TagTarget,
        target: (key: String, size: Long) -> IdentificationTarget,
    ): LocalFile? {
        val descriptor = open(uri) ?: return null
        return descriptor.use {
            val reply = client.read(tagTarget(it), it, name, markLegacy = true)
            val report = (reply as? TaggerResult.Replied)?.value?.report ?: return null
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
            LocalFile(
                target = target(key, it.statSize.coerceAtLeast(0)),
                fileName = name ?: uri.lastPathSegment.orEmpty(),
                tags = tags,
                duration = duration,
                format = report.container,
                folder = folder,
                uri = uri.toString(),
            )
        }
    }

    private fun open(uri: Uri): ParcelFileDescriptor? = try {
        resolver.openFileDescriptor(uri, "r")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Gone, or its grant has ended.
        null
    }

    /** What the provider says of the file. */
    private class Provided(val name: String?, val size: Long?)

    private fun provided(uri: Uri): Provided = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    Provided(
                        cursor.getString(0)?.trim()?.ifEmpty { null },
                        if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 },
                    )
                }
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.let { Provided(it.name, if (it.isFile) it.length() else null) }
            else -> null
        }
    }.getOrNull() ?: Provided(uri.lastPathSegment?.trim()?.ifEmpty { null }, null)

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

        /** Whether the device or the app speaks a language written in Cyrillic, as the tag pass decides (TI §3.1). */
        private fun systemCyrillic(context: Context): Boolean {
            val locales = listOf(Resources.getSystem().configuration.locales, context.resources.configuration.locales)
            return LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
        }
    }
}
