package io.github.lorddetson.piplayer.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

/** Copies a chosen image into the covers folder (docs/library.md §13.2). */
object CoverImport {

    /** The longer side of a copy at most. */
    const val MAX_SIDE = 1_500

    private const val JPEG_QUALITY = 92
    private const val TAG = "CoverImport"

    /**
     * Decodes [image] (the decoder applies its orientation), scales it down to [MAX_SIDE] on its longer side and saves it
     * under a new name in [dir]: JPEG, or PNG when it has transparency. Null if it is no image. Blocks.
     */
    fun copy(context: Context, image: Uri, dir: File): CoverCopy? {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, image)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = MAX_SIDE.toFloat() / maxOf(width, height)
                if (scale < 1f) {
                    decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Not an image: $image", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "Not an image: $image", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to $image", e)
            return null
        }
        val transparent = transparent(bitmap)
        val name = UUID.randomUUID().toString() + if (transparent) ".png" else ".jpg"
        dir.mkdirs()
        val temporary = File(dir, "$name.tmp")
        val file = File(dir, name)
        try {
            temporary.outputStream().use { out ->
                val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!bitmap.compress(format, if (transparent) 100 else JPEG_QUALITY, out)) throw IOException("Could not encode the copy")
            }
            if (!temporary.renameTo(file)) throw IOException("Could not rename $temporary")
        } catch (e: IOException) {
            Log.w(TAG, "Could not save a copy of $image", e)
            temporary.delete()
            return null
        }
        return CoverCopy(name, bitmap.width, bitmap.height)
    }

    /** Whether a pixel of [bitmap] is not opaque; an alpha channel alone does not count. */
    private fun transparent(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 != 0xFF }) return true
        }
        return false
    }
}
