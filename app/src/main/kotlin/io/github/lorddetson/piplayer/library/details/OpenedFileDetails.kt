package io.github.lorddetson.piplayer.library.details

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.outsideTarget
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the details of a file opened from another app from the file itself (docs/library.md §18.5): its tags and technical
 * data through `:tagger` by [client], counted against the file as any outside read, its name, size and type from its
 * provider, its embedded picture by [probe]. Nothing is stored. Off the main thread.
 */
class OpenedFileDetails(
    context: Context,
    private val client: TaggerClient,
    private val probe: PictureProbe,
    private val cyrillicLanguage: () -> Boolean = { systemCyrillic(context) },
    private val legacyEncoding: suspend () -> LegacyEncoding? = { LibrarySettings(context).readLegacyEncoding() },
) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** What the provider says of the file. */
    private class Provided(val name: String?, val size: Long?)

    /** The details of the file at [uri], or null when it cannot be opened at all. */
    suspend fun read(uri: String): TrackDetails? = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        val descriptor = open(parsed) ?: return@withContext null
        val provided = provided(parsed)
        val name = provided.name ?: parsed.lastPathSegment ?: uri
        val (report, size) = descriptor.use { file ->
            readTags(uri, file, name) to (provided.size ?: file.statSize.takeIf { it >= 0 })
        }
        val tags = report?.let { TrackTags.from(LegacyText.decode(it.properties, cyrillicLanguage(), legacyEncoding()).properties) }
        val pictures = report?.pictures.orEmpty()
        // The probe reads the picture as the player does; without a read it is still worth a look.
        val picture = if (report == null || pictures.isNotEmpty()) probe.embedded(uri) else null
        val cover = when {
            picture != null -> CoverDetail(
                CoverSource.EMBEDDED,
                picture.width,
                picture.height,
                (pictures.firstOrNull { it.pictureType == FRONT_COVER } ?: pictures.firstOrNull())?.sizeBytes?.toLong(),
            )
            pictures.isNotEmpty() -> CoverDetail(CoverSource.EMBEDDED)
            else -> CoverDetail(CoverSource.NONE)
        }
        TrackDetailsBuilder.of(
            DetailInputs(
                fileId = null,
                file = FileFacts(
                    displayName = name,
                    size = size,
                    mimeType = runCatching { resolver.getType(parsed) }.getOrNull(),
                    read = report != null,
                    tagState = if (report == null) TagState.CANNOT_READ else null,
                    format = report?.container,
                    codec = report?.codec,
                    sampleRate = report?.audio?.sampleRate,
                    bitDepth = report?.bitDepth,
                    channels = report?.audio?.channels,
                    bitrateKbps = report?.audio?.bitrateKbps,
                    tagLengthMs = report?.audio?.lengthMs?.toLong(),
                ),
                tags = tags,
                cover = cover,
            ),
        )
    }

    /** One read of [file] through `:tagger`; null when it failed in any way, which the details then say. */
    private suspend fun readTags(uri: String, file: ParcelFileDescriptor, name: String): TagReport? = try {
        val result = client.read(outsideTarget(uri, file), file, name, markLegacy = true)
        (result as? TaggerResult.Replied)?.value?.report?.takeIf { it.error == null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "The tags of an opened file could not be read", e)
        null
    }

    private fun open(uri: Uri): ParcelFileDescriptor? = try {
        resolver.openFileDescriptor(uri, "r")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Gone, or its grant has ended.
        null
    }

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
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it) }?.let { Provided(it.name, it.length()) }
            else -> null
        }
    }.getOrNull() ?: Provided(null, null)

    private companion object {
        const val TAG = "OpenedFileDetails"
        const val FRONT_COVER = "Front Cover"

        /** Whether the device or the app speaks a language written in Cyrillic, as the tag pass decides (TI §3.6). */
        fun systemCyrillic(context: Context): Boolean {
            val locales = listOf(Resources.getSystem().configuration.locales, context.resources.configuration.locales)
            return LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
        }
    }
}
