package io.github.lorddetson.piplayer.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

/** Pictures of one color, as covers come: PNG bytes, or decoded. */
object TestImages {

    const val BLUE = 0xFF3D5AFE.toInt()

    fun bitmap(width: Int, height: Int = width, color: Int = BLUE): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    fun png(width: Int, height: Int = width, color: Int = BLUE): ByteArray =
        ByteArrayOutputStream().also { bitmap(width, height, color).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    fun cover(size: Int = 64): ImageBitmap = bitmap(size).asImageBitmap()
}
