package io.github.lorddetson.piplayer.library.details

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import io.github.lorddetson.piplayer.covers.CoverStep
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.covers.EmbeddedPictures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pictures of files on the device, for their details (docs/library.md §18.3): the embedded one as the player reads it,
 * of which only the header is decoded, and whether Android has a thumbnail.
 */
class AndroidPictureProbe(context: Context) : PictureProbe {

    private val app = context.applicationContext

    override suspend fun embedded(uri: String): PictureSize? = safely {
        val bytes = EmbeddedPictures.read(app, uri.toUri()) ?: return@safely null
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) PictureSize(bounds.outWidth, bounds.outHeight) else null
        }
    }

    override suspend fun hasThumbnail(uri: String): Boolean = safely {
        withContext(Dispatchers.IO) {
            app.contentResolver.loadThumbnail(uri.toUri(), Size(THUMBNAIL_PX, THUMBNAIL_PX), null).recycle()
            true
        }
    } ?: false

    /**
     * Whether the lazy Cover Art Archive [step] has a picture, loaded by the loader as a list would load it: from its caches,
     * otherwise through the client with its gate, limiter and failure entries (docs/library.md §18.3).
     */
    override suspend fun online(step: CoverStep.Caa): Boolean = safely { Covers.loader(app).step(step, CAA_PX) != null } ?: false

    /** [block], or null when the file cannot be read now: details then show no such picture. */
    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "No picture could be read", e)
        null
    }

    private companion object {
        const val TAG = "AndroidPictureProbe"

        /** The smallest step of the loader (docs/library.md §13.3): enough to know that there is one. */
        const val THUMBNAIL_PX = 96

        /** Cover Art Archive's list size, `front-250` (docs/library.md §13.3). */
        const val CAA_PX = 250
    }
}
