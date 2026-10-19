package io.github.lorddetson.piplayer.nativelib.resample

import io.github.lorddetson.piplayer.nativelib.NativeLibrary
import java.util.Objects

class ResamplerException(message: String) : RuntimeException(message)

/**
 * Mono sample-rate conversion with the resampler of speexdsp (a windowed-sinc filter that also removes what the
 * output rate cannot carry, so nothing folds back into the audible band). The output starts aligned with the input,
 * without the filter's delay, and [flush] gives exactly as many samples in total as the input's length converted to
 * the output rate. A resampler is used from one thread at a time.
 */
class Resampler(val inputRate: Int, val outputRate: Int, quality: Int = DEFAULT_QUALITY) : AutoCloseable {

    private var handle: Long
    private var flushed = false

    init {
        require(inputRate > 0 && outputRate > 0) { "Sample rates must be positive: $inputRate to $outputRate Hz" }
        require(quality in MIN_QUALITY..MAX_QUALITY) { "Quality must be $MIN_QUALITY to $MAX_QUALITY: $quality" }
        NativeLibrary.load()
        handle = nativeNew(inputRate, outputRate, quality)
        if (handle == 0L) throw ResamplerException("speexdsp could not resample $inputRate to $outputRate Hz")
    }

    /** Converts the next [length] samples of [input] and returns the output they complete. */
    fun process(input: FloatArray, offset: Int = 0, length: Int = input.size - offset): FloatArray {
        checkOpen()
        Objects.checkFromIndexSize(offset, length, input.size)
        if (length == 0) return FloatArray(0)
        return nativeProcess(handle, input, offset, length) ?: throw ResamplerException("speexdsp refused the samples")
    }

    /** Ends the input: returns the last output samples, held back by the filter so far. */
    fun flush(): FloatArray {
        checkOpen()
        flushed = true
        return nativeFlush(handle) ?: throw ResamplerException("speexdsp could not flush")
    }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private fun checkOpen() {
        check(handle != 0L) { "The resampler is closed" }
        check(!flushed) { "The resampler is flushed" }
    }

    companion object {
        /** speexdsp quality 5: far sharper than fingerprinting needs, and cheap enough for two minutes of audio. */
        const val DEFAULT_QUALITY = 5
        private const val MIN_QUALITY = 0
        private const val MAX_QUALITY = 10

        @JvmStatic private external fun nativeNew(inputRate: Int, outputRate: Int, quality: Int): Long
        @JvmStatic
        private external fun nativeProcess(handle: Long, input: FloatArray, offset: Int, length: Int): FloatArray?
        @JvmStatic private external fun nativeFlush(handle: Long): FloatArray?
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
