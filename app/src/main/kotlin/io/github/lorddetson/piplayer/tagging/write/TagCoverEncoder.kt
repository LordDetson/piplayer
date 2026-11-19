package io.github.lorddetson.piplayer.tagging.write

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import io.github.lorddetson.piplayer.tagging.TagArtwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TagCoverPreset { COMPATIBLE, CAR_STEREO, ORIGINAL }

/** Resized presets use a software JPEG encoder, while Original keeps the detected image bytes. */
internal suspend fun encodeFrontCover(file: File, preset: TagCoverPreset = TagCoverPreset.COMPATIBLE): TagArtwork? = withContext(Dispatchers.IO) {
    try {
        if (preset == TagCoverPreset.ORIGINAL) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val mime = bounds.outMimeType ?: return@withContext null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            return@withContext TagArtwork.of(mime, "", file.readBytes())
        }
        val maxEdge = if (preset == TagCoverPreset.CAR_STEREO) 600 else 1_000
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val edge = maxOf(size.width, size.height)
            if (edge > maxEdge) {
                decoder.setTargetSize(
                    (size.width.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
                    (size.height.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
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
