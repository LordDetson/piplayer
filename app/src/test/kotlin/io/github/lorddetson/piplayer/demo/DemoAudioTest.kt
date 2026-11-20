package io.github.lorddetson.piplayer.demo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoAudioTest {
    @Test
    fun `two original samples carry stable identifiers and short durations`() {
        assertEquals(
            mapOf("morning-steps" to 8_000L, "quiet-orbit" to 10_000L),
            DemoAudio.samples.associate { it.id to it.durationMs },
        )
    }

    @Test
    fun `each sample is a compact PCM WAV with a consistent length and duration`() {
        for (sample in DemoAudio.samples) {
            val bytes = render(sample)
            val header = littleEndian(bytes)
            assertEquals("RIFF", bytes.ascii(0, 4))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals("WAVE", bytes.ascii(8, 4))
            assertEquals("fmt ", bytes.ascii(12, 4))
            assertEquals(16, header.getInt(16))
            assertEquals(1, header.getShort(20).toInt(), "PCM")
            assertEquals(1, header.getShort(22).toInt(), "Mono")
            assertEquals(SAMPLE_RATE, header.getInt(24))
            assertEquals(SAMPLE_RATE * 2, header.getInt(28))
            assertEquals(2, header.getShort(32).toInt(), "Two bytes per frame")
            assertEquals(16, header.getShort(34).toInt())
            assertEquals("data", bytes.ascii(36, 4))
            assertEquals(bytes.size - HEADER_BYTES, header.getInt(40))
            assertEquals(sample.durationMs, header.getInt(40) * 1_000L / header.getInt(28))
            assertTrue(bytes.size < 450_000)
        }
    }

    @Test
    fun `samples contain audible bounded audio with quiet starts and ends`() {
        for (sample in DemoAudio.samples) {
            val buffer = littleEndian(render(sample)).apply { position(HEADER_BYTES) }
            val frames = buffer.remaining() / 2
            var peak = 0
            var audible = 0
            repeat(frames) { frame ->
                val value = abs(buffer.getShort().toInt())
                peak = maxOf(peak, value)
                if (value > 200) audible++
                if (frame == 0 || frame == frames - 1) assertTrue(value <= 1, sample.id)
            }
            assertTrue(peak in 1_000..16_384, "${sample.id} peak was $peak")
            assertTrue(audible > frames / 3, "${sample.id} contains too little audible audio")
        }
    }

    @Test
    fun `repeated renders match exactly while the two melodies differ`() {
        val first = render(DemoAudio.Sample.MORNING_STEPS)
        val second = render(DemoAudio.Sample.QUIET_ORBIT)
        assertArrayEquals(first, render(DemoAudio.Sample.MORNING_STEPS))
        assertArrayEquals(second, render(DemoAudio.Sample.QUIET_ORBIT))
        val prefixEnd = HEADER_BYTES + SAMPLE_RATE * 2
        assertFalse(
            first.copyOfRange(HEADER_BYTES, prefixEnd).contentEquals(second.copyOfRange(HEADER_BYTES, prefixEnd)),
        )
    }

    @Test
    fun `the writer leaves stream ownership with the caller`() {
        val sink = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() {
                closed = true
            }
        }

        DemoAudio.writeSample(DemoAudio.Sample.MORNING_STEPS, sink)

        assertTrue(sink.size() > HEADER_BYTES)
        assertFalse(sink.closed)
    }

    private fun render(sample: DemoAudio.Sample): ByteArray {
        val sink = ByteArrayOutputStream()
        DemoAudio.writeSample(sample, sink)
        return sink.toByteArray().also { assertTrue(it.size > HEADER_BYTES, "${sample.id} has no PCM data") }
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteArray.ascii(start: Int, count: Int): String = String(this, start, count, Charsets.US_ASCII)

    private companion object {
        const val HEADER_BYTES = 44
        const val SAMPLE_RATE = 22_050
    }
}
