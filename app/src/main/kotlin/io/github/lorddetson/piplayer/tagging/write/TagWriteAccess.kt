package io.github.lorddetson.piplayer.tagging.write

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import java.io.FileNotFoundException

/** Keeps the write pipeline independent of how access was obtained. The caller owns the returned descriptor. */
internal interface TagWriteAccess {
    fun requestAccess(files: List<Uri>): IntentSender?
    fun openForWrite(file: Uri): ParcelFileDescriptor
}

internal class MediaStoreWriteAccess(private val context: Context) : TagWriteAccess {
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

    private fun requireMediaItem(file: Uri) {
        require(file.scheme == "content" && file.authority == MediaStore.AUTHORITY &&
            file.pathSegments.size > 1 && file.lastPathSegment?.toLongOrNull() != null) {
            "A write requires a MediaStore item URI"
        }
    }
}
