package io.github.lorddetson.piplayer.library.identity

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import io.github.lorddetson.piplayer.identification.fingerprint.MediaCodecPcmSource
import io.github.lorddetson.piplayer.identification.fingerprint.NativeFingerprinting
import io.github.lorddetson.piplayer.library.db.FingerprintEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The fingerprint cache of the library database (docs/library.md §8.3), filled by fingerprints made with the decoders
 * of identification at background priority, of at most 120 s of audio.
 */
class LibraryFingerprints(
    private val context: Context,
    private val library: LibraryDatabase,
    private val uriOf: (KeyedFile) -> Uri,
    private val clock: () -> Long = System::currentTimeMillis,
) : FingerprintSource {

    override suspend fun cached(contentKey: String): CachedPrint? =
        library.library().fingerprint(contentKey)?.let { CachedPrint(items(it.fingerprint), it.format) }

    override suspend fun compute(file: KeyedFile): IntArray? = withContext(background) {
        val raw = try {
            context.contentResolver.openFileDescriptor(uriOf(file), "r")?.use { descriptor ->
                NativeFingerprinting.calculator().fingerprint(MediaCodecPcmSource(context, descriptor.fileDescriptor))
                    .fingerprint.raw
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No fingerprint of file ${file.fileId}", e)
            null
        } ?: return@withContext null
        library.library().saveFingerprint(FingerprintEntity(file.contentKey, bytes(raw), file.format, clock()))
        raw
    }

    companion object {
        private const val TAG = "LibraryFingerprints"

        private val background = Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "fingerprints").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        fun bytes(items: IntArray): ByteArray = ByteBuffer.allocate(items.size * 4).apply { items.forEach(::putInt) }.array()

        fun items(bytes: ByteArray): IntArray = ByteBuffer.wrap(bytes).asIntBuffer().let { IntArray(it.remaining()).also(it::get) }
    }
}
