package io.github.lorddetson.piplayer.library.playlists

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.settings.LibrarySettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** M3U8 documents from and to the system file picker (docs/library.md §16.6). */
class PlaylistFiles(context: Context) {

    private val app = context.applicationContext

    /** Paths with the directories of the volumes attached now. */
    fun paths(): M3uPaths = M3uPaths(volumes(app))

    /** A new playlist from the document [uri]; what it found. */
    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val read = input.readNBytesCompat(MAX_BYTES + 1)
            if (read.size > MAX_BYTES) throw IOException("The playlist is larger than $MAX_BYTES bytes")
            read
        } ?: throw IOException("The playlist could not be opened")
        val name = displayName(uri)?.let { it.substringBeforeLast('.').ifBlank { it } }?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.playlist_default_name)
        val folder = if (DocumentsContract.isDocumentUri(app, uri)) M3uPaths.folderOf(uri.authority, DocumentsContract.getDocumentId(uri)) else null
        val text = M3u.decode(bytes, cyrillicLanguage(), LibrarySettings(app).readLegacyEncoding())
        if (Library.mayRead(app)) Library.awaitReady(app, READY_TIMEOUT_MS)
        Library.playlists(app).import(text, name, folder, paths())
    }

    /** Writes [playlistId] into the document [uri]; how many entries it wrote. */
    suspend fun export(playlistId: Long, uri: Uri): Int = withContext(Dispatchers.IO) {
        if (Library.mayRead(app)) Library.awaitReady(app, READY_TIMEOUT_MS)
        val entries = Library.playlists(app).exportEntries(playlistId, paths())
        val output = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The playlist could not be created")
        output.use { it.write(M3u.write(entries).toByteArray(Charsets.UTF_8)) }
        entries.size
    }

    private fun displayName(uri: Uri): String? {
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Whether one of the device's or the app's languages makes Windows-1251 a candidate, as for tags (docs/library.md §5.7). */
    private fun cyrillicLanguage(): Boolean {
        val locales = listOf(Resources.getSystem().configuration.locales, app.resources.configuration.locales)
        return LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        /** A playlist of 5 000 long paths takes well under 1 MB; anything far larger is not a playlist. */
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val READY_TIMEOUT_MS = 30_000L

        /** The directory of each attached volume by its media index name (`external_primary` and the cards). */
        fun volumes(context: Context): Map<String, String> {
            val storage = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
            return storage.storageVolumes.mapNotNull { volume ->
                val name = volume.mediaStoreVolumeName ?: return@mapNotNull null
                val directory = volume.directory ?: return@mapNotNull null
                name to directory.absolutePath
            }.toMap()
        }
    }
}
