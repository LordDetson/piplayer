package io.github.lorddetson.piplayer.identification.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.fingerprint.DecodingException
import io.github.lorddetson.piplayer.identification.fingerprint.FingerprintResult
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.identity.LibraryFingerprints
import io.github.lorddetson.piplayer.nativelib.chromaprint.Chromaprint
import io.github.lorddetson.piplayer.tagging.Tagger
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** The native library's encoder (TI §3.2): stored raw items into the string AcoustID takes. */
object ChromaprintEncoder : FingerprintEncoder {
    override fun encode(raw: IntArray): String = Chromaprint.encode(raw)
}

/** Stage 3's decode in the app (TI §3.2 "Threads"), shared by the library's fingerprint cache and the debug spike's. */
object UserLaneDecoder {

    /**
     * The fingerprint of [file], decoded on the user's lane of [LibraryFingerprints] without caching it. Throws an
     * [IOException] when the file cannot be opened or decoded, a [DecodingException] for a decoder or extractor that
     * fails in its own way.
     */
    suspend fun decode(context: Context, file: LocalFile): FingerprintResult = try {
        LibraryFingerprints.decode(context, Uri.parse(file.uri), LibraryFingerprints.Lane.USER)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        // A decoder or extractor that fails in its own way: the file is not fingerprinted.
        throw DecodingException.Failed("The file could not be fingerprinted: ${e.message}", e)
    }
}

/**
 * The fingerprint cache of the library database that [library] reaches (TI §3.2, §10.1), as stage 3 uses it: a file is
 * decoded on the user's lane of [LibraryFingerprints] and its fingerprint cached with its decoder path, keeping the
 * AcoustID track ID cached for the key.
 */
class LibraryFingerprintCache(
    private val context: Context,
    private val library: LibraryAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FingerprintCache {

    override suspend fun cached(contentKey: String): CachedFingerprint? = withContext(ioDispatcher) {
        // The block runs under the library's lock, held by its thread: the read blocks that thread.
        library.use { database -> runBlocking { database.library().fingerprint(contentKey) } }
    }?.let { CachedFingerprint(LibraryFingerprints.items(it.fingerprint)) }

    override suspend fun compute(file: LocalFile): ComputedFingerprint {
        // Decoded without the library's lock, which a decode of seconds must not hold.
        val result = UserLaneDecoder.decode(context, file)
        // Cached under the lock, so that a rebuild never closes the database under the write; a write that fails costs
        // the next run a decode, not this run its lookup.
        try {
            withContext(ioDispatcher) {
                library.use { database ->
                    runBlocking { database.library().saveComputedFingerprint(LibraryFingerprints.entity(file.target.contentKey, file.format, result)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The fingerprint could not be cached", e)
        }
        return ComputedFingerprint(result.fingerprint.raw, result.fingerprint.encoded)
    }

    override suspend fun keepAcoustIdTrack(contentKey: String, trackId: String) = withContext(ioDispatcher) {
        library.use { database -> runBlocking { database.identificationCache().keepAcoustIdTrack(contentKey, trackId) } }
    }
}

private const val TAG = "LibraryFingerprintCache"

/** The pipeline of the app's main process (TI §3 "One run"). */
object AppPipeline {

    /**
     * The app's pipeline: the app's services, whose gate is the user's stored choice (TI §9) and which a run waits to
     * read, stage 0 through the library and `:tagger`, the library's fingerprint cache and the native encoder.
     */
    fun of(context: Context): IdentificationPipeline = over(context, IdentificationServices.of(context))

    /**
     * The same parts over [services], such as the debug spike's own, with [fingerprints] instead of the library's
     * fingerprint cache when given (the spike keeps its own in memory, TI §3 "One run").
     */
    fun over(
        context: Context,
        services: IdentificationServices,
        fingerprints: FingerprintCache? = null,
    ): IdentificationPipeline {
        val library = LibraryAccess.app(context)
        return IdentificationPipeline(
            services = services,
            local = AndroidLocalStage(context, Tagger.client(context), library),
            fingerprints = fingerprints ?: LibraryFingerprintCache(context.applicationContext, library),
            encoder = ChromaprintEncoder,
        )
    }
}
