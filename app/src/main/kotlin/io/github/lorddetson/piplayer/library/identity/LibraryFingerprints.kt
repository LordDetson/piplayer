package io.github.lorddetson.piplayer.library.identity

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.NativeFingerprinting
import io.github.lorddetson.piplayer.identification.fingerprint.PcmSource
import io.github.lorddetson.piplayer.library.db.FingerprintEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * The fingerprint cache of the library database (docs/library.md §8.3), filled by fingerprints of at most 120 s of
 * audio, made with the decoders of identification. One entry, [compute], serves three lanes (TI §3.2 "Threads"):
 * re-attachment's computations run one after another on a thread of background priority, single-file identification
 * that the user waits for has a thread of its own at normal priority, so it never waits behind them, and a batch's
 * computations run on a thread of their own at background priority, so they never take the user's lane (TI §8).
 * [sources] makes the decoding source of an open file; the lane's thread decodes too.
 */
class LibraryFingerprints(
    private val context: Context,
    private val library: LibraryDatabase,
    private val uriOf: (KeyedFile) -> Uri,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sources: (descriptor: ParcelFileDescriptor, cancelled: () -> Boolean) -> PcmSource =
        { descriptor, cancelled ->
            MediaCodecPcmSource(context, descriptor.fileDescriptor, cancelled, checkError = descriptor::checkError)
        },
) : FingerprintSource {

    /** The thread a computation waits for. */
    enum class Lane(internal val dispatcher: ExecutorCoroutineDispatcher) {
        /** Re-attachment (TI §3.1 step 4): one thread at background priority. */
        REATTACHMENT(lane("fingerprints", Process.THREAD_PRIORITY_BACKGROUND)),

        /**
         * Single-file identification that the user waits for: one thread at normal priority, apart from
         * re-attachment's. Batches are started by the user but not waited for, so they take [BATCH] instead, never
         * this lane (TI §8).
         */
        USER(lane("fingerprints-user", Process.THREAD_PRIORITY_DEFAULT)),

        /**
         * A batch (TI §8): one thread at background priority, apart from re-attachment's. A batch runs while music
         * may play, and its one thread means at most one decoder runs for the batch then; a batch always decodes
         * here, never on [USER].
         */
        BATCH(lane("fingerprints-batch", Process.THREAD_PRIORITY_BACKGROUND)),
    }

    override suspend fun cached(contentKey: String): CachedPrint? =
        library.library().fingerprint(contentKey)?.let { CachedPrint(items(it.fingerprint), it.format) }

    override suspend fun compute(file: KeyedFile): IntArray? = try {
        compute(uriOf(file), file.contentKey, file.format, Lane.REATTACHMENT).fingerprint.raw
    } catch (e: CancellationException) {
        throw e
    } catch (e: DecodingException) {
        // Expected for some files (ALAC without a decoder, a pipe past the copy's bounds): no stack trace.
        Log.w(TAG, "No fingerprint of file ${file.fileId}: ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "No fingerprint of file ${file.fileId}", e)
        null
    }

    /**
     * The fingerprint of the file at [uri], decoded on [lane]'s thread and cached under [contentKey] with the file's
     * [format] and the decoder path, keeping the AcoustID track ID cached for that key. Throws when the file cannot be
     * opened or decoded (a [DecodingException] says why, an unsupported format among others); a cancelled caller stops
     * the copy or the decoder soon.
     */
    suspend fun compute(uri: Uri, contentKey: String, format: String?, lane: Lane): FingerprintResult {
        val result = decode(context, uri, lane, sources)
        // Cached after leaving the lane: Room suspends to its own threads, and coming back to the lane's single thread
        // would queue the end of this request behind the next request's whole decode.
        library.library().saveComputedFingerprint(entity(contentKey, format, result, clock()))
        return result
    }

    companion object {
        private const val TAG = "LibraryFingerprints"

        private fun lane(name: String, priority: Int) = Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(priority)
                task.run()
            }, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /**
         * The fingerprint of the file at [uri], decoded on [lane]'s thread by the source [sources] makes, without
         * caching it; [compute] caches it too. Single-file identification caches it itself, under the library's lock.
         */
        suspend fun decode(
            context: Context,
            uri: Uri,
            lane: Lane,
            sources: (descriptor: ParcelFileDescriptor, cancelled: () -> Boolean) -> PcmSource = { descriptor, cancelled ->
                MediaCodecPcmSource(context, descriptor.fileDescriptor, cancelled, checkError = descriptor::checkError)
            },
        ): FingerprintResult = withContext(lane.dispatcher) {
            val job = coroutineContext.job
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("No descriptor for $uri")
            // The calculator keeps to the lane's thread, so the lane is busy until the fingerprint is made.
            descriptor.use {
                NativeFingerprinting.calculator(dispatcher = lane.dispatcher)
                    .fingerprint(sources(it) { !job.isActive })
            }
        }

        /** The cache row of [result], computed at [computedAt] for [contentKey]. */
        fun entity(
            contentKey: String,
            format: String?,
            result: FingerprintResult,
            computedAt: Long = System.currentTimeMillis(),
        ) = FingerprintEntity(
            contentKey, bytes(result.fingerprint.raw), format, computedAt, decoderPath = result.source.decoderPath,
        )

        fun bytes(items: IntArray): ByteArray = ByteBuffer.allocate(items.size * 4).apply { items.forEach(::putInt) }.array()

        fun items(bytes: ByteArray): IntArray = ByteBuffer.wrap(bytes).asIntBuffer().let { IntArray(it.remaining()).also(it::get) }
    }
}
