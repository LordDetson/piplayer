package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FlacFramesTest {

    /** "fLaC", a last-block STREAMINFO header and STREAMINFO with unknown frame sizes, as a piped encoder leaves it. */
    private fun streamInfo(blockSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val bits = StringBuilder()
        fun put(value: Long, width: Int) = bits.append(value.toString(2).padStart(width, '0'))
        put(blockSize.toLong(), 16)
        put(blockSize.toLong(), 16)
        put(0, 24)
        put(0, 24)
        put(sampleRate.toLong(), 20)
        put(channels - 1L, 3)
        put(bitsPerSample - 1L, 5)
        put(96_000, 36)
        val info = bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray() + ByteArray(16)
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info
    }

    private fun flac(vararg initializationData: ByteArray) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC)
        .setInitializationData(initializationData.toList())
        .build()

    @Test
    fun `the largest frame holds every sample verbatim, with a side channel one bit wider`() {
        val format = flac(streamInfo(blockSize = 32_768, sampleRate = 48_000, channels = 2, bitsPerSample = 24))

        // 16 + 2 * (2 + 32768 * 25 / 8) + 2
        assertThat(FlacFrames.maxFrameSize(format)).isEqualTo(204_822)
    }

    @Test
    fun `a common stream needs much less`() {
        val format = flac(streamInfo(blockSize = 4_096, sampleRate = 44_100, channels = 2, bitsPerSample = 16))

        // 16 + 2 * (2 + 4096 * 17 / 8) + 2
        assertThat(FlacFrames.maxFrameSize(format)).isEqualTo(17_430)
    }

    @Test
    fun `without STREAMINFO there is no bound`() {
        assertThat(FlacFrames.maxFrameSize(flac())).isNull()
        assertThat(FlacFrames.maxFrameSize(flac("OggS".toByteArray() + ByteArray(40)))).isNull()
        assertThat(FlacFrames.maxFrameSize(flac("fLaC".toByteArray()))).isNull()
    }
}
