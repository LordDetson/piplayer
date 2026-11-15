package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.nativelib.chromaprint.ChromaprintSession
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import io.github.lorddetson.piplayer.nativelib.resample.Resampler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The fingerprint path with the native parts: speexdsp's resampler and Chromaprint. */
object NativeFingerprinting {

    val resamplers = ResamplerFactory { inputRate, outputRate -> NativeResampler(Resampler(inputRate, outputRate)) }

    fun sink(): FingerprintSink = ChromaprintSink(ChromaprintSession())

    /** A calculator that decodes and fingerprints on [dispatcher]. */
    fun calculator(
        maxSeconds: Int = FingerprintCalculator.MAX_SECONDS,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): FingerprintCalculator = FingerprintCalculator(resamplers, ::sink, maxSeconds, dispatcher)
}

private class NativeResampler(private val resampler: Resampler) : MonoResampler {
    override fun process(input: FloatArray, offset: Int, length: Int): FloatArray =
        resampler.process(input, offset, length)

    override fun flush(): FloatArray = resampler.flush()

    override fun close() = resampler.close()
}

private class ChromaprintSink(private val session: ChromaprintSession) : FingerprintSink {
    override fun feed(samples: ShortArray, length: Int) = session.feed(samples, 0, length)

    override fun finish(): Fingerprint = session.finish()

    override fun close() = session.close()
}
