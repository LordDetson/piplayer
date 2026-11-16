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
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.tagging.outsideTarget
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Stage 0 in the app (TI §3.1, §3 "One run"): [LocalReads] over the library database that [library] reaches and over
 * `:tagger`, reached by [client]. A read through `:tagger` is counted against the file like every read, and its tags are
 * decoded with the legacy-encoding setting. Walks of the length run in `:tagger` too. Nothing is stored: the tag pass
 * keeps the library's own values.
 */
class AndroidLocalStage(
    context: Context,
    private val client: TaggerClient,
    private val library: LibraryAccess,
    cyrillicLanguage: () -> Boolean = { systemCyrillic(context) },
    legacyEncoding: suspend () -> LegacyEncoding? = { LibrarySettings(context).readLegacyEncoding() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalStage {

    private val resolver = context.applicationContext.contentResolver

    private val reads = LocalReads(
        library = { fileId -> storedFile(fileId) },
        tagger = { uri, name, fileId -> readThrough(uri, name, fileId) },
        cyrillicLanguage = cyrillicLanguage,
        legacyEncoding = legacyEncoding,
    )

    override suspend fun read(file: FileRef): LocalFile? = withContext(ioDispatcher) {
        when (file) {
            is FileRef.Library -> reads.library(file.fileId)
            is FileRef.Opened -> {
                val uri = Uri.parse(file.uri)
                reads.opened(file.uri, provided(uri), fallbackName = uri.lastPathSegment.orEmpty())
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

    /** What the library database holds of [fileId], read under the library's lock. */
    private fun storedFile(fileId: Long): StoredFile? {
        val (row, track) = library.use { database ->
            runBlocking { database.library().file(fileId) to database.library().trackOf(fileId) }
        }
        row ?: return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(row.volumeName), row.mediaStoreId)
        return StoredFile(row, track, uri.toString())
    }

    /** One read through `:tagger`, counted against the library file [fileId] or the file outside the library. */
    private suspend fun readThrough(uri: String, name: String?, fileId: Long?): TaggerRead? {
        val descriptor = open(Uri.parse(uri)) ?: return null
        return descriptor.use {
            val target = if (fileId != null) TagTarget.LibraryFile(fileId) else outsideTarget(uri, it)
            val reply = client.read(target, it, name, markLegacy = true)
            (reply as? TaggerResult.Replied)?.value?.report?.let { report -> TaggerRead(report, it.statSize) }
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

    private fun provided(uri: Uri): ProvidedFile = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    ProvidedFile(
                        cursor.getString(0)?.trim()?.ifEmpty { null },
                        if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 },
                    )
                }
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.let { ProvidedFile(it.name, if (it.isFile) it.length() else null) }
            else -> null
        }
    }.getOrNull() ?: ProvidedFile(uri.lastPathSegment?.trim()?.ifEmpty { null }, null)

    companion object {

        /** The length the tag pass stored, or what is left to do for it (TI §3.1). */
        fun stored(row: AudioFileEntity): StageDuration = LocalReads.stored(row)

        /** Whether the device or the app speaks a language written in Cyrillic, as the tag pass decides (TI §3.1). */
        private fun systemCyrillic(context: Context): Boolean {
            val locales = listOf(Resources.getSystem().configuration.locales, context.resources.configuration.locales)
            return LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
        }
    }
}
