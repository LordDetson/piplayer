package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.nativelib.chromaprint.Chromaprint
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * What a source decoded, for reports. [endedEarly] says why decoding stopped before the end of the file, when the
 * decoder failed after some audio had come out; that audio is kept.
 */
data class SourceInfo(
    val mimeType: String,
    val decoderName: String,
    val durationUs: Long?,
    val endedEarly: String? = null,
)

/** Decoded audio, delivered in pieces. */
fun interface PcmSource {

    /**
     * Decodes from the start and hands every piece of PCM to [consumer] together with its format, until the consumer
     * returns false or the stream ends. Blocks the calling thread; a consumer may throw to cancel.
     */
    fun decode(consumer: (pcm: ByteBuffer, format: PcmFormat) -> Boolean): SourceInfo
}

/** A mono float resampler to a fixed output rate. */
interface MonoResampler : AutoCloseable {

    fun process(input: FloatArray, offset: Int = 0, length: Int = input.size - offset): FloatArray

    /** Ends the input and returns the last samples; the resampler takes no input afterwards. */
    fun flush(): FloatArray
}

fun interface ResamplerFactory {
    fun create(inputRate: Int, outputRate: Int): MonoResampler
}

/** Takes 16-bit mono PCM at [Chromaprint.SAMPLE_RATE] and makes the fingerprint. */
interface FingerprintSink : AutoCloseable {

    fun feed(samples: ShortArray, length: Int)

    fun finish(): Fingerprint
}

/**
 * The fingerprint of a file and how it was made. [fedSamples] counts the samples given to Chromaprint, at
 * [Chromaprint.SAMPLE_RATE]; [inputFormat] is the decoder's first PCM format, null when it put out nothing.
 * [reachedEnd] is true when the whole file was decoded before the time limit (and the decoder did not stop early), so
 * [fedSeconds] is its exact length.
 */
data class FingerprintResult(
    val fingerprint: Fingerprint,
    val fedSamples: Long,
    val inputFormat: PcmFormat?,
    val source: SourceInfo,
    val reachedEnd: Boolean,
) {
    val fedSeconds: Double get() = fedSamples.toDouble() / Chromaprint.SAMPLE_RATE
}

/**
 * Stage 3 of identification up to the fingerprint (TI §3.2): decoded PCM is turned into mono, resampled to
 * [Chromaprint.SAMPLE_RATE] (or passed on unchanged when it already has that rate) and fed to Chromaprint as 16-bit
 * samples, stopping after [maxSeconds] of audio. When the stream ends sooner, the resampler's tail is fed too. A
 * format change in the middle of the stream ends the old resampler and starts a new one.
 */
class FingerprintCalculator(
    private val resamplers: ResamplerFactory,
    private val sinks: () -> FingerprintSink,
    private val maxSeconds: Int = MAX_SECONDS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun fingerprint(source: PcmSource): FingerprintResult = withContext(dispatcher) {
        val job = coroutineContext.job
        Run(sinks()).use { run ->
            val info = source.decode { pcm, format ->
                // A cancelled caller stops the decoder at its next piece.
                job.ensureActive()
                run.take(pcm, format)
            }
            run.finish(info)
        }
    }

    /** One computation: the sink, the current resampler and what was fed so far. */
    private inner class Run(private val sink: FingerprintSink) : AutoCloseable {
        private val limit = maxSeconds.toLong() * Chromaprint.SAMPLE_RATE
        private var fed = 0L
        private var stopped = false
        private var firstFormat: PcmFormat? = null
        private var format: PcmFormat? = null
        private var resampler: MonoResampler? = null

        /** Returns false once the limit is reached, which stops the decoder. */
        fun take(pcm: ByteBuffer, pcmFormat: PcmFormat): Boolean {
            if (pcmFormat != format) start(pcmFormat)
            val mono = PcmConverter.toMono(pcm, pcmFormat)
            feed(resampler?.process(mono) ?: mono)
            stopped = fed >= limit
            return !stopped
        }

        fun finish(info: SourceInfo): FingerprintResult {
            if (!stopped) endResampler()
            val reachedEnd = !stopped && info.endedEarly == null
            return FingerprintResult(sink.finish(), fed, firstFormat, info, reachedEnd)
        }

        private fun start(pcmFormat: PcmFormat) {
            endResampler()
            if (firstFormat == null) firstFormat = pcmFormat
            format = pcmFormat
            if (pcmFormat.sampleRate != Chromaprint.SAMPLE_RATE) {
                resampler = resamplers.create(pcmFormat.sampleRate, Chromaprint.SAMPLE_RATE)
            }
        }

        /** Feeds the current resampler's tail and lets it go. */
        private fun endResampler() {
            val ending = resampler ?: return
            resampler = null
            ending.use { feed(it.flush()) }
        }

        private fun feed(samples: FloatArray) {
            val count = minOf(samples.size.toLong(), limit - fed).toInt()
            if (count <= 0) return
            sink.feed(PcmConverter.toPcm16(samples, 0, count), count)
            fed += count
        }

        override fun close() {
            try {
                resampler?.close()
            } finally {
                sink.close()
            }
        }
    }

    companion object {
        const val MAX_SECONDS = 120
    }
}
