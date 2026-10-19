package io.github.lorddetson.piplayer.identification.fingerprint

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PcmConverterTest {

    private fun format(channels: Int, encoding: PcmEncoding) = PcmFormat(44_100, channels, encoding)

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it.toShort()) }; flip() }

    private fun pcm24(vararg samples: Int): ByteBuffer = ByteBuffer.allocate(samples.size * 3).apply {
        samples.forEach { put(it.toByte()); put((it shr 8).toByte()); put((it shr 16).toByte()) }
        flip()
    }

    private fun pcm32(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putInt(it) }; flip() }

    private fun pcmFloat(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putFloat(it) }; flip() }

    @Test
    fun `16-bit mono is scaled by 32768`() {
        val mono = PcmConverter.toMono(pcm16(0, 16_384, -32_768, 32_767), format(1, PcmEncoding.PCM_16BIT))
        assertThat(mono).containsExactly(floatArrayOf(0f, 0.5f, -1f, 32_767f / 32_768f), within(1e-7f))
    }

    @Test
    fun `stereo is averaged into one channel`() {
        val mono = PcmConverter.toMono(pcm16(16_384, -16_384, 16_384, 16_384, 0, -32_768), format(2, PcmEncoding.PCM_16BIT))
        assertThat(mono).containsExactly(floatArrayOf(0f, 0.5f, -0.5f), within(1e-7f))
    }

    @Test
    fun `5_1 is averaged over all six channels`() {
        // One frame with only the left surround channel (fifth in the decoder's order) set.
        val mono = PcmConverter.toMono(pcmFloat(0f, 0f, 0f, 0f, 0.6f, 0f), format(6, PcmEncoding.PCM_FLOAT))
        assertThat(mono).containsExactly(floatArrayOf(0.1f), within(1e-6f))
    }

    @Test
    fun `8-bit PCM is unsigned around 128`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(128.toByte(), 255.toByte(), 0, 64))
        val mono = PcmConverter.toMono(buffer, format(1, PcmEncoding.PCM_8BIT))
        assertThat(mono).containsExactly(floatArrayOf(0f, 127f / 128f, -1f, -0.5f), within(1e-7f))
    }

    @Test
    fun `24-bit packed samples are sign-extended`() {
        val mono = PcmConverter.toMono(pcm24(0x400000, 0xC00000, 0x7FFFFF, 0x800000), format(1, PcmEncoding.PCM_24BIT_PACKED))
        assertThat(mono).containsExactly(floatArrayOf(0.5f, -0.5f, 8_388_607f / 8_388_608f, -1f), within(1e-7f))
    }

    @Test
    fun `32-bit integer samples are scaled by 2 to the 31st`() {
        val mono = PcmConverter.toMono(pcm32(0x40000000, Int.MIN_VALUE, 0), format(1, PcmEncoding.PCM_32BIT))
        assertThat(mono).containsExactly(floatArrayOf(0.5f, -1f, 0f), within(1e-7f))
    }

    @Test
    fun `float samples pass through, also beyond full scale`() {
        val mono = PcmConverter.toMono(pcmFloat(0.25f, -0.75f, 1.5f, 1.5f), format(2, PcmEncoding.PCM_FLOAT))
        assertThat(mono).containsExactly(floatArrayOf(-0.25f, 1.5f), within(1e-7f))
    }

    @Test
    fun `reads from the position to the limit and leaves the position alone`() {
        val buffer = pcm16(1_000, 16_384, -16_384, 1_000)
        buffer.position(2).limit(6)
        val mono = PcmConverter.toMono(buffer, format(1, PcmEncoding.PCM_16BIT))
        assertThat(mono).containsExactly(floatArrayOf(0.5f, -0.5f), within(1e-7f))
        assertThat(buffer.position()).isEqualTo(2)
        assertThat(buffer.limit()).isEqualTo(6)
    }

    @Test
    fun `samples are read as little-endian whatever order the buffer is set to`() {
        val buffer = pcm16(16_384).order(ByteOrder.BIG_ENDIAN)
        assertThat(PcmConverter.toMono(buffer, format(1, PcmEncoding.PCM_16BIT))).containsExactly(0.5f)
    }

    @Test
    fun `an empty buffer gives no samples`() {
        assertThat(PcmConverter.toMono(ByteBuffer.allocate(0), format(2, PcmEncoding.PCM_16BIT))).isEmpty()
    }

    @Test
    fun `a partial frame is refused`() {
        assertThatThrownBy { PcmConverter.toMono(pcm16(1, 2, 3), format(2, PcmEncoding.PCM_16BIT)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @CsvSource(
        "0.5, 16384",
        "-0.5, -16384",
        "1.0, 32767",
        "-1.0, -32768",
        "1.5, 32767",
        "-2.0, -32768",
        "0.0000122, 0",
        "0.0000183, 1",
        "-0.0000183, -1",
    )
    fun `16-bit output is rounded and clipped`(sample: Float, expected: Short) {
        assertThat(PcmConverter.toPcm16(floatArrayOf(sample))).containsExactly(expected)
    }

    @Test
    fun `NaN becomes silence`() {
        assertThat(PcmConverter.toPcm16(floatArrayOf(Float.NaN))).containsExactly(0)
    }

    @Test
    fun `16-bit output honours offset and length`() {
        val pcm = PcmConverter.toPcm16(floatArrayOf(0.1f, 0.5f, -0.5f, 0.9f), offset = 1, length = 2)
        assertThat(pcm).containsExactly(16_384, -16_384)
    }

    @Test
    fun `out-of-range offset and length are refused`() {
        assertThatThrownBy { PcmConverter.toPcm16(floatArrayOf(0f, 0f), offset = 1, length = 2) }
            .isInstanceOf(IndexOutOfBoundsException::class.java)
    }

    @ParameterizedTest
    @CsvSource("2, PCM_16BIT", "3, PCM_8BIT", "4, PCM_FLOAT", "21, PCM_24BIT_PACKED", "22, PCM_32BIT")
    fun `Android encodings map to PCM encodings`(android: Int, expected: PcmEncoding) {
        assertThat(PcmEncoding.fromAndroid(android)).isEqualTo(expected)
    }

    @Test
    fun `the default and compressed encodings are not PCM`() {
        // ENCODING_DEFAULT (1) leaves the choice to the decoder; 5 is AC-3.
        assertThat(PcmEncoding.fromAndroid(1)).isNull()
        assertThat(PcmEncoding.fromAndroid(5)).isNull()
    }
}
