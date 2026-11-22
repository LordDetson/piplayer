package io.github.lorddetson.piplayer.playback.mood

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class PcmMoodAccumulatorTest {
    @Test
    fun `estimates the tempo of quiet and loud music from recurring transients`() {
        for (bpm in listOf(72, 96, 124, 158, 184)) {
            for (gain in listOf(0.07f, 0.75f)) {
                val mood = analyze(pulses(bpm, gain))
                assertThat(mood?.tempoBpm).describedAs("tempo of %s BPM at gain %s", bpm, gain)
                    .isBetween(bpm - 3, bpm + 3)
            }
        }
    }

    @Test
    fun `energy reflects both the tempo and the intensity of the samples`() {
        val quiet = requireNotNull(analyze(pulses(100, 0.07f)))
        val loud = requireNotNull(analyze(pulses(100, 0.75f)))
        val fast = requireNotNull(analyze(pulses(160, 0.75f)))
        assertThat(quiet.intensity).isBetween(0f, 1f).isLessThan(loud.intensity)
        assertThat(quiet.energy).isLessThan(loud.energy)
        assertThat(fast.intensity).isCloseTo(loud.intensity, within(0.02f))
        assertThat(fast.energy).isGreaterThan(loud.energy)
    }

    @Test
    fun `a steady tone has measurable intensity without an invented beat`() {
        val tone = FloatArray(sampleRate * 12) { index ->
            (0.4 * sin(2 * PI * index * 100 / sampleRate)).toFloat()
        }
        val mood = requireNotNull(analyze(tone))
        assertThat(mood.tempoBpm).isNull()
        assertThat(mood.intensity).isGreaterThan(0.5f)
        assertThat(mood.energy).isEqualTo(mood.intensity)
    }

    @Test
    fun `silence and extremely short samples leave the mood unknown`() {
        assertThat(analyze(FloatArray(sampleRate * 12))).isNull()
        assertThat(analyze(FloatArray(5) { Float.NaN })).isNull()
        assertThat(analyze(FloatArray(50) { 0.5f })).isNull()
    }

    @Test
    fun `chunk boundaries do not affect the result and the PCM window is bounded`() {
        val song = pulses(110, 0.5f)
        val chunked = PcmMoodAccumulator()
        var start = 0
        while (start < song.size) {
            val end = (start + 1537).coerceAtMost(song.size)
            assertThat(chunked.add(song.copyOfRange(start, end).powers(), sampleRate)).isTrue()
            start = end
        }
        assertThat(chunked.result()).isEqualTo(analyze(song))
        assertThat(chunked.add(FloatArray(sampleRate * 60), sampleRate)).isFalse()
    }

    private fun analyze(samples: FloatArray): TrackMood? =
        PcmMoodAccumulator().apply { add(samples.powers(), sampleRate) }.result()

    private fun FloatArray.powers() = FloatArray(size) { this[it] * this[it] }

    private fun pulses(bpm: Int, gain: Float): FloatArray = FloatArray(sampleRate * 12) { index ->
        val seconds = index.toDouble() / sampleRate
        val phase = (seconds * bpm / 60) % 1
        val envelope = 0.08 + 0.92 * exp(-phase * 22)
        (gain * envelope * sin(2 * PI * seconds * 100)).toFloat()
    }

    private val sampleRate = 4000
}
