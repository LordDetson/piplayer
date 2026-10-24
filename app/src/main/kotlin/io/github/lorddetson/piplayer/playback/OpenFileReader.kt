package io.github.lorddetson.piplayer.playback

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Reads what an opened file says about itself before it plays (docs/playback.md §5.2): its tags when the platform can
 * read them, otherwise its file name. The player itself supplies the cover from the same tags.
 */
class OpenFileReader(context: Context) {

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    /** The file at [uri], or null if this process cannot open it. Blocks: call it off the main thread. */
    fun read(uri: Uri): OpenedFile? {
        if (!canRead(uri)) return null
        val displayName = displayName(uri)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.coerceAtLeast(0) ?: 0
        } catch (e: RuntimeException) {
            // A file without tags is still playable; the file name below is enough.
            Log.d(TAG, "No readable tags in $uri", e)
        } finally {
            runCatching { retriever.release() }
        }
        return OpenedFile(
            uri = uri.toString(),
            title = title ?: titleFromName(displayName) ?: uri.lastPathSegment ?: uri.toString(),
            artist = artist,
            album = album,
            durationMs = duration,
        )
    }

    /** Whether this process can open [uri] now; a restored opened file is skipped when its grant has expired. */
    fun canRead(uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).canRead() } ?: false
            else -> false
        }
    }.getOrDefault(false)

    private fun displayName(uri: Uri): String? = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).name }
            else -> null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun MediaMetadataRetriever.metadata(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun titleFromName(name: String?): String? {
        if (name == null) return null
        val dot = name.lastIndexOf('.')
        return (if (dot > 0) name.substring(0, dot) else name).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "OpenFileReader"
    }
}
