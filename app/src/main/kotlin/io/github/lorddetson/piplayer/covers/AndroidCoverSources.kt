package io.github.lorddetson.piplayer.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The picture embedded in a file, as Media3's extractors read it for playback (docs/library.md §13.1). */
object EmbeddedPictures {

    /**
     * The bytes of the picture of the file at [uri]: a front cover, otherwise the last picture, the way the player picks
     * it; null without one or for what no extractor reads. Throws IOException when the file cannot be read now.
     */
    @OptIn(UnstableApi::class)
    suspend fun read(context: Context, uri: Uri): ByteArray? {
        val retriever = MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build()
        try {
            val groups = try {
                retriever.retrieveTrackGroups().await()
            } catch (_: ParserException) {
                // No extractor reads it, or it is malformed: it never yields a picture.
                return null
            }
            val metadata = MediaMetadata.Builder()
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (f in 0 until group.length) group.getFormat(f).metadata?.let(metadata::populateFromMetadata)
            }
            return metadata.build().artworkData
        } finally {
            // Also ends a retrieval cut short.
            retriever.close()
        }
    }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addListener({
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: java.util.concurrent.CancellationException) {
                    continuation.cancel(e)
                }
            }, Runnable::run)
            continuation.invokeOnCancellation { cancel(true) }
        }
}

/** Decodes and encodes covers as bitmaps (docs/library.md §13.3). */
object BitmapCodec : ImageCodec<Bitmap> {

    private const val JPEG_QUALITY = 90

    override fun encode(image: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        val software = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        return if (software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
    }

    override fun decode(bytes: ByteArray, size: Int): Bitmap? = decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), size)

    override fun weight(image: Bitmap): Long = image.allocationByteCount.toLong()

    /** A JPEG starts with its start-of-image marker. */
    override fun isEncoded(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /** Decodes [file] for [size]; null if it is missing or no image. */
    fun decode(file: File, size: Int): Bitmap? = if (file.isFile) decode(ImageDecoder.createSource(file), size) else null

    /** Decodes [source] so that its shorter side is [size] at most ([CoverSizes.target]); the decoder scales as it decodes. */
    fun decode(source: ImageDecoder.Source, size: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = CoverSizes.target(info.size.width, info.size.height, size)
            if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** [bitmap] scaled down so that its shorter side is [size], when it is larger. */
    fun scaled(bitmap: Bitmap, size: Int): Bitmap {
        val (width, height) = CoverSizes.target(bitmap.width, bitmap.height, size)
        return if (width == bitmap.width && height == bitmap.height) bitmap else bitmap.scale(width, height)
    }
}

/** The sources of covers on the device: own copies in [ownDir], embedded pictures and system thumbnails. */
class AndroidCoverSources(private val context: Context, private val ownDir: File) : CoverSources<Bitmap> {

    override suspend fun load(step: CoverStep, size: Int): Bitmap? = when (step) {
        is CoverStep.Own -> withContext(Dispatchers.IO) { BitmapCodec.decode(File(ownDir, step.fileName), size) }
        is CoverStep.Embedded -> EmbeddedPictures.read(context, step.file.uri.toUri())?.let { bytes ->
            withContext(Dispatchers.Default) { BitmapCodec.decode(bytes, size) }
        }
        is CoverStep.Thumbnail -> thumbnail(step.file.uri.toUri(), size)?.let { BitmapCodec.scaled(it, size) }
    }

    /** The system thumbnail of [uri], or null when Android has none; a load cut short cancels the request. */
    private suspend fun thumbnail(uri: Uri, size: Int): Bitmap? = coroutineScope {
        val signal = CancellationSignal()
        // Started at once and resumed in the thread that cancels: a busy pool must not keep the signal from going off.
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                signal.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { context.contentResolver.loadThumbnail(uri, Size(size, size), signal) }
        } catch (_: OperationCanceledException) {
            throw CancellationException("The thumbnail was no longer needed")
        } catch (_: IOException) {
            // No embedded picture and no image in the folder.
            null
        } finally {
            watcher.cancel()
        }
    }
}
