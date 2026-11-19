package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException

/** Keeps the write pipeline independent of how access was obtained. The caller owns the returned descriptor. */
internal interface TagWriteAccess {
    fun requestAccess(files: List<Uri>): IntentSender?
    fun openForWrite(file: Uri): ParcelFileDescriptor
    suspend fun onVerified(file: Uri) = Unit
}

internal class MediaStoreWriteAccess(
    private val context: Context,
    private val scan: (String) -> Unit = { path -> MediaScannerConnection.scanFile(context, arrayOf(path), null, null) },
) : TagWriteAccess {
    override fun requestAccess(files: List<Uri>): IntentSender? {
        val missing = files.distinct().filterNot { file ->
            requireMediaItem(file)
            context.checkUriPermission(
                file, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return null
        require(missing.size <= MEDIASTORE_WRITE_REQUEST_LIMIT)
        return MediaStore.createWriteRequest(context.contentResolver, missing).intentSender
    }

    override fun openForWrite(file: Uri): ParcelFileDescriptor {
        requireMediaItem(file)
        return context.contentResolver.openFileDescriptor(file, "rwt") ?: throw FileNotFoundException(file.toString())
    }

    override suspend fun onVerified(file: Uri) {
        requireMediaItem(file)
        try {
            // Read the path of an existing item for the scanner. All file writes use the approved item descriptor.
            val path = context.contentResolver.query(file, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            if (!path.isNullOrBlank()) scan(path)
        } catch (error: RuntimeException) {
            // Indexing is eventual. A scanner error does not undo a committed file write.
            Log.w("MediaStoreWriteAccess", "Could not rescan the verified media item", error)
        }
    }

    private fun requireMediaItem(file: Uri) {
        require(file.scheme == "content" && file.authority == MediaStore.AUTHORITY &&
            file.pathSegments.size > 1 && file.lastPathSegment?.toLongOrNull() != null) {
            "A write requires a MediaStore item URI"
        }
    }
}
