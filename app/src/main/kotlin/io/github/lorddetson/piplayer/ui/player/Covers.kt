package io.github.lorddetson.piplayer.ui.player

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Embedded covers for the screens (docs/playback.md §3.4). */
object Covers {

    /**
     * Decodes [bytes], scaled down so that neither side exceeds [maxSizePx]; null if they are no image. The decoder
     * scales while it decodes, so a 3000x3000 cover never takes its full 36 MB. Blocks: call it off the main thread.
     */
    fun decode(bytes: ByteArray, maxSizePx: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = maxSizePx.toFloat() / maxOf(width, height)
            if (scale < 1f) {
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
