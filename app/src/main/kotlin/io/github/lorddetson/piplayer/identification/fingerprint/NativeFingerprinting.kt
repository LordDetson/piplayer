package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.nativelib.chromaprint.ChromaprintSession
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import io.github.lorddetson.piplayer.nativelib.resample.Resampler

/** The fingerprint path with the native parts: speexdsp's resampler and Chromaprint. */
object NativeFingerprinting {

    val resamplers = ResamplerFactory { inputRate, outputRate -> NativeResampler(Resampler(inputRate, outputRate)) }

    fun sink(): FingerprintSink = ChromaprintSink(ChromaprintSession())

    fun calculator(maxSeconds: Int = FingerprintCalculator.MAX_SECONDS): FingerprintCalculator =
        FingerprintCalculator(resamplers, ::sink, maxSeconds)
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
