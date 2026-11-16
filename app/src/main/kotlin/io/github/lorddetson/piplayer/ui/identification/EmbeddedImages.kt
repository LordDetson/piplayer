package io.github.lorddetson.piplayer.ui.identification

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.inspector.MetadataRetriever
import io.github.lorddetson.piplayer.identification.caa.ImageHeader
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Every picture embedded in a file, as the player's extractors read them (TI §6, review screen B): ID3 `APIC` frames, FLAC
 * and Vorbis picture blocks and MP4 `covr` images, in the order the extractors give them, the same bytes once.
 */
object EmbeddedImages {

    private const val TAG = "EmbeddedImages"

    /** The pictures of the file at [uri]; none for a file no extractor reads, null for one that cannot be read now. */
    @OptIn(UnstableApi::class)
    suspend fun read(context: Context, uri: Uri): List<EmbeddedImage>? = try {
        val retriever = MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build()
        try {
            val groups = retriever.retrieveTrackGroups().await()
            val pictures = mutableListOf<ByteArray>()
            for (g in 0 until groups.length) {
                val group = groups[g]
                for (f in 0 until group.length) {
                    val metadata = group.getFormat(f).metadata ?: continue
                    for (i in 0 until metadata.length()) {
                        when (val entry = metadata[i]) {
                            is ApicFrame -> pictures += entry.pictureData
                            is PictureFrame -> pictures += entry.pictureData
                            else -> Unit
                        }
                    }
                }
            }
            pictures.distinctBy { it.contentHashCode() }
                .filter { ImageHeader.format(it) != null }
                .map { EmbeddedImage(it, ImageHeader.size(it)) }
        } finally {
            retriever.close()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ParserException) {
        emptyList()
    } catch (e: Exception) {
        Log.d(TAG, "The pictures of a file could not be read", e)
        null
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
