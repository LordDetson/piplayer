package io.github.lorddetson.piplayer.tagging.write

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.lorddetson.piplayer.tagging.TagArtwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The compatible embedding preset, scaled at decode time and encoded as JPEG. */
internal suspend fun encodeFrontCover(file: File): TagArtwork? = withContext(Dispatchers.IO) {
    try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val edge = maxOf(size.width, size.height)
            if (edge > 1_000) {
                decoder.setTargetSize(
                    (size.width.toLong() * 1_000 / edge).toInt().coerceAtLeast(1),
                    (size.height.toLong() * 1_000 / edge).toInt().coerceAtLeast(1),
                )
            }
        }
        try {
            val encoded = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, encoded)) TagArtwork.of("image/jpeg", "", encoded.toByteArray())
            else null
        } finally {
            bitmap.recycle()
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
