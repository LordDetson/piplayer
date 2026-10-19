package io.github.lorddetson.piplayer.nativelib.resample

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resampler that feeds Chromaprint (TI §3.2, §11.1): what the output rate cannot carry is filtered out before it
 * folds back, the band Chromaprint uses passes unchanged and in phase, and the output length is exact.
 */
@RunWith(AndroidJUnit4::class)
class ResamplerTest {

    private val output = 11_025

    private fun sine(frequency: Double, rate: Int, seconds: Double, amplitude: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * frequency * it / rate)).toFloat() }

    private fun resampleAll(input: FloatArray, inputRate: Int): FloatArray = Resampler(inputRate, output).use {
        it.process(input) + it.flush()
    }

    /** Root mean square over the middle of the signal, away from the edges. */
    private fun rms(samples: FloatArray, rate: Int): Double {
        val margin = rate / 5
        val middle = samples.copyOfRange(margin, samples.size - margin)
        return sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
    }

    private fun decibels(ratio: Double) = 20 * log10(ratio)

    @Test
    fun aNineKilohertzToneIsAtLeast40DecibelsBelowAOneKilohertzTone() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val pass = rms(resampleAll(sine(1_000.0, rate, 3.0), rate), output)
            val stop = rms(resampleAll(sine(9_000.0, rate, 3.0), rate), output)
            assertThat(decibels(pass / stop)).describedAs("$rate Hz").isGreaterThanOrEqualTo(40.0)
        }
    }

    @Test
    fun aOneKilohertzToneKeepsItsLevelWithinHalfADecibel() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val level = rms(resampleAll(sine(1_000.0, rate, 3.0), rate), output)
            assertThat(decibels(level / (0.5 / sqrt(2.0)))).describedAs("$rate Hz").isCloseTo(0.0, within(0.5))
        }
    }

    @Test
    fun theOutputIsInPhaseWithTheInputWithoutTheFilterDelay() {
        for (rate in listOf(44_100, 48_000)) {
            val resampled = resampleAll(sine(1_000.0, rate, 2.0), rate)
            val ideal = sine(1_000.0, output, 2.0)
            val worst = (output / 5 until output * 9 / 5).maxOf { abs(resampled[it] - ideal[it]) }
            assertThat(worst).describedAs("$rate Hz").isLessThan(0.02f)
        }
    }

    @Test
    fun theTotalLengthIsTheInputLengthAtTheOutputRate() {
        for ((rate, frames) in listOf(44_100 to 132_337, 48_000 to 96_001, 96_000 to 10_007)) {
            val total = resampleAll(FloatArray(frames), rate).size
            val expected = (frames.toLong() * output + rate / 2) / rate
            assertThat(total.toLong()).describedAs("$frames frames at $rate Hz").isEqualTo(expected)
        }
    }

    @Test
    fun theOutputDoesNotDependOnHowTheInputIsCut() {
        val input = sine(1_000.0, 48_000, 1.5)
        val whole = resampleAll(input, 48_000)
        val random = Random(4)
        val pieces = Resampler(48_000, output).use { resampler ->
            var offset = 0
            var result = FloatArray(0)
            while (offset < input.size) {
                val length = minOf(random.nextInt(1, 5_000), input.size - offset)
                result += resampler.process(input, offset, length)
                offset += length
            }
            result + resampler.flush()
        }
        assertThat(pieces).isEqualTo(whole)
    }

    @Test
    fun invalidRatesAndQualitiesAreRefused() {
        assertThatThrownBy { Resampler(0, output) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Resampler(44_100, -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Resampler(44_100, output, quality = 11) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Resampler(44_100, output, quality = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun anInputRangeOutsideTheArrayIsRefused() {
        Resampler(44_100, output).use { resampler ->
            assertThatThrownBy { resampler.process(FloatArray(10), 5, 6) }
                .isInstanceOf(IndexOutOfBoundsException::class.java)
        }
    }

    @Test
    fun aFlushedOrClosedResamplerTakesNoMoreInput() {
        val resampler = Resampler(44_100, output)
        resampler.process(FloatArray(100))
        resampler.flush()
        assertThatThrownBy { resampler.process(FloatArray(100)) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { resampler.flush() }.isInstanceOf(IllegalStateException::class.java)
        resampler.close()
        resampler.close()
        assertThatThrownBy { resampler.process(FloatArray(100)) }.isInstanceOf(IllegalStateException::class.java)
    }
}
