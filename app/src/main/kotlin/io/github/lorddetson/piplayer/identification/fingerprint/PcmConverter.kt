package io.github.lorddetson.piplayer.identification.fingerprint

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Objects
import kotlin.math.roundToInt

/** Sample encodings a decoder may put out, with their size in bytes. */
enum class PcmEncoding(val bytesPerSample: Int) {
    PCM_8BIT(1),
    PCM_16BIT(2),
    PCM_24BIT_PACKED(3),
    PCM_32BIT(4),
    PCM_FLOAT(4),
    ;

    companion object {
        /** Maps an `AudioFormat.ENCODING_*` value; null for compressed or unknown encodings. */
        fun fromAndroid(encoding: Int): PcmEncoding? = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> PCM_8BIT
            AudioFormat.ENCODING_PCM_16BIT -> PCM_16BIT
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PCM_24BIT_PACKED
            AudioFormat.ENCODING_PCM_32BIT -> PCM_32BIT
            AudioFormat.ENCODING_PCM_FLOAT -> PCM_FLOAT
            else -> null
        }
    }
}

/** Interleaved PCM as a decoder delivers it. */
data class PcmFormat(val sampleRate: Int, val channelCount: Int, val encoding: PcmEncoding) {
    val bytesPerFrame: Int get() = channelCount * encoding.bytesPerSample
}

/** Turns decoder output into the mono samples the fingerprint takes (TI §3.2). */
object PcmConverter {

    /**
     * Mono samples on the scale -1 to 1 from the interleaved little-endian PCM between [buffer]'s position and limit,
     * which must hold whole frames: every channel weighs the same, so a 5.1 source is averaged over all six. 8-bit PCM
     * is unsigned, as in WAV. The buffer's position is left unchanged.
     */
    fun toMono(buffer: ByteBuffer, format: PcmFormat): FloatArray {
        val bytes = buffer.remaining()
        require(bytes % format.bytesPerFrame == 0) {
            "$bytes bytes are not whole frames of ${format.bytesPerFrame} bytes"
        }
        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val channels = format.channelCount
        val mono = FloatArray(bytes / format.bytesPerFrame)
        if (format.encoding == PcmEncoding.PCM_16BIT) {
            // What decoders deliver almost always: read in bulk.
            val shorts = ShortArray(bytes / 2).also { pcm.asShortBuffer().get(it) }
            for (frame in mono.indices) {
                var sum = 0
                for (channel in 0 until channels) sum += shorts[frame * channels + channel]
                mono[frame] = sum / (32_768f * channels)
            }
            return mono
        }
        for (frame in mono.indices) {
            var sum = 0f
            repeat(channels) { sum += sample(pcm, format.encoding) }
            mono[frame] = sum / channels
        }
        return mono
    }

    /** Reads the next sample at the buffer's position and moves past it. */
    private fun sample(pcm: ByteBuffer, encoding: PcmEncoding): Float = when (encoding) {
        PcmEncoding.PCM_8BIT -> ((pcm.get().toInt() and 0xFF) - 128) / 128f
        PcmEncoding.PCM_16BIT -> pcm.getShort() / 32_768f
        PcmEncoding.PCM_24BIT_PACKED -> {
            val low = pcm.get().toInt() and 0xFF
            val middle = pcm.get().toInt() and 0xFF
            val high = pcm.get().toInt() // signed: carries the sign into the upper bits
            ((high shl 16) or (middle shl 8) or low) / 8_388_608f
        }
        PcmEncoding.PCM_32BIT -> (pcm.getInt() / 2_147_483_648.0).toFloat()
        PcmEncoding.PCM_FLOAT -> pcm.getFloat()
    }

    /** 16-bit samples: rounded to the nearest step and clipped to the 16-bit range; NaN becomes silence. */
    fun toPcm16(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): ShortArray {
        Objects.checkFromIndexSize(offset, length, samples.size)
        return ShortArray(length) { index ->
            val scaled = samples[offset + index] * 32_768f
            if (scaled.isNaN()) 0 else scaled.coerceIn(-32_768f, 32_767f).roundToInt().toShort()
        }
    }
}
