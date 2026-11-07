package io.github.lorddetson.piplayer.covers

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/** The picture embedded in a file, as Media3's extractors read it for playback (docs/library.md §13.1). */
object EmbeddedPictures {

    /** The bytes of the picture of the file at [uri]: a front cover, otherwise the last picture; null without one. */
    suspend fun read(context: Context, uri: Uri): ByteArray? = null
}

/** Decodes and encodes covers as bitmaps (docs/library.md §13.3). */
object BitmapCodec : ImageCodec<Bitmap> {

    override fun encode(image: Bitmap): ByteArray? = null

    override fun decode(bytes: ByteArray, size: Int): Bitmap? = null

    override fun weight(image: Bitmap): Long = image.allocationByteCount.toLong()

    /** Decodes [file] for [size]; null if it is no image. */
    fun decode(file: File, size: Int): Bitmap? = null
}

/** The sources of covers on the device: own copies in [ownDir], embedded pictures and system thumbnails. */
class AndroidCoverSources(private val context: Context, private val ownDir: File) : CoverSources<Bitmap> {

    override suspend fun load(step: CoverStep, size: Int): Bitmap? = null
}
