package io.github.lorddetson.piplayer.nativelib.chromaprint

import io.github.lorddetson.piplayer.nativelib.NativeLibrary
import java.util.Objects

/** A Chromaprint fingerprint: the raw 32-bit items and the compressed, base64-encoded form AcoustID takes. */
class Fingerprint(val raw: IntArray, val encoded: String)

class ChromaprintException(message: String) : RuntimeException(message)

/**
 * One fingerprint computation. Chromaprint is built without a resampler, so it takes 16-bit PCM at
 * [Chromaprint.SAMPLE_RATE] only; any other rate or channel count fails at once with [ChromaprintException].
 * A session is used from one thread at a time.
 */
class ChromaprintSession(sampleRate: Int = Chromaprint.SAMPLE_RATE, channels: Int = 1) : AutoCloseable {

    private var handle: Long
    private var finished = false

    init {
        NativeLibrary.load()
        handle = nativeNew(Chromaprint.ALGORITHM)
        if (handle == 0L) throw ChromaprintException("Chromaprint could not create a context")
        if (!nativeStart(handle, sampleRate, channels)) {
            close()
            throw ChromaprintException("Chromaprint refused $sampleRate Hz with $channels channel(s)")
        }
    }

    /** Adds samples; the PCM may arrive in pieces of any size. */
    fun feed(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(handle != 0L) { "The session is closed" }
        check(!finished) { "The session is finished" }
        Objects.checkFromIndexSize(offset, length, samples.size)
        if (!nativeFeed(handle, samples, offset, length)) throw ChromaprintException("Chromaprint refused the samples")
    }

    /** Ends the input and returns the fingerprint; the session cannot be fed afterwards. */
    fun finish(): Fingerprint {
        check(handle != 0L) { "The session is closed" }
        check(!finished) { "The session is finished" }
        finished = true
        if (!nativeFinish(handle)) throw ChromaprintException("Chromaprint could not finish the fingerprint")
        return Fingerprint(nativeRawFingerprint(handle), nativeEncodedFingerprint(handle))
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    // Internal for Chromaprint.encode: the natives stay on this class, which the consumer rules keep for JNI_OnLoad.
    internal companion object {
        @JvmStatic external fun nativeNew(algorithm: Int): Long
        @JvmStatic external fun nativeStart(handle: Long, sampleRate: Int, channels: Int): Boolean
        @JvmStatic external fun nativeFeed(handle: Long, samples: ShortArray, offset: Int, length: Int): Boolean
        @JvmStatic external fun nativeFinish(handle: Long): Boolean
        @JvmStatic external fun nativeRawFingerprint(handle: Long): IntArray
        @JvmStatic external fun nativeEncodedFingerprint(handle: Long): String
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeEncode(raw: IntArray, algorithm: Int): String?
    }
}

object Chromaprint {

    const val SAMPLE_RATE = 11_025

    /** Chromaprint's default algorithm (TEST2, which fpcalc calls "2"); AcoustID's fingerprints use it. */
    const val ALGORITHM = 1

    /** Fingerprint of mono [pcm] at [SAMPLE_RATE] in one call. */
    fun fingerprint(pcm: ShortArray): Fingerprint = ChromaprintSession().use { session ->
        session.feed(pcm)
        session.finish()
    }

    /** Chromaprint's algorithms run from TEST1 (0) to TEST5 (4). */
    private val ALGORITHMS = 0..4

    /** The compressed form stores the number of items in 24 bits. */
    private const val MAX_ITEMS = 0xFF_FFFF

    /**
     * The compressed, base64-encoded form of stored [raw] items, as AcoustID takes it (TI §3.2): for the items of a
     * session it equals [Fingerprint.encoded], so a cache can keep the raw items only. No items give an empty string.
     */
    fun encode(raw: IntArray, algorithm: Int = ALGORITHM): String {
        require(algorithm in ALGORITHMS) { "Unknown Chromaprint algorithm $algorithm" }
        require(raw.size <= MAX_ITEMS) { "${raw.size} items are more than a fingerprint holds" }
        NativeLibrary.load()
        return ChromaprintSession.nativeEncode(raw, algorithm)
            ?: throw ChromaprintException("Chromaprint could not encode the fingerprint")
    }
}
