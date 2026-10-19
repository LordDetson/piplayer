package io.github.lorddetson.piplayer.identification.fingerprint

import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.nativelib.chromaprint.Chromaprint
import io.github.lorddetson.piplayer.nativelib.chromaprint.Fingerprint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every format, rate and channel layout of the decoding fixtures (scripts/generate_decoding_fixtures.py, a 2 s tone
 * of 1 kHz) goes through Media3's extractor and the platform decoder at the expected rate and channel count:
 * compressed formats as 16-bit PCM, WAV in its own encoding (it skips the decoder). The start is trimmed to the sample
 * (encoder delay, Opus pre-skip), so the audio is never shorter than 2 s; at the end the platform trims MP3 padding,
 * but AAC padding and the last Vorbis and Opus packets stay, at most one frame. After the whole fingerprint path the
 * tone is still 1 kHz at the same level.
 */
@RunWith(Parameterized::class)
class DecodingFixturesTest(private val fixture: Fixture) {

    data class Fixture(
        val name: String,
        val mimeType: String,
        val sampleRate: Int,
        val channels: Int,
        val encoding: PcmEncoding = PcmEncoding.PCM_16BIT,
        /** What the end may run over 2 s: untrimmed padding of at most one frame. */
        val endPaddingSeconds: Double = 0.0,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val SECONDS = 2.0

        /** Rounding of the length to whole samples, here and after resampling. */
        private const val SLACK_SECONDS = 0.001

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf(
            Fixture("mp3-44100-stereo.mp3", "audio/mpeg", 44_100, 2),
            // One AAC frame is 1024 samples, a long Vorbis block 2048, an Opus packet at most 120 ms.
            Fixture("aac-44100-stereo.m4a", "audio/mp4a-latm", 44_100, 2, endPaddingSeconds = 0.024),
            Fixture("vorbis-48000-mono.ogg", "audio/vorbis", 48_000, 1, endPaddingSeconds = 0.043),
            Fixture("opus-48000-stereo.opus", "audio/opus", 48_000, 2, endPaddingSeconds = 0.025),
            Fixture("flac-96000-24bit-stereo.flac", "audio/flac", 96_000, 2),
            Fixture("flac-48000-16bit-5.1.flac", "audio/flac", 48_000, 6),
            // Frames of about 150 KB, larger than the decoder's default input buffer, and no frame size in the header.
            Fixture("flac-48000-24bit-stereo-big-frames.flac", "audio/flac", 48_000, 2),
            Fixture("wav-44100-16bit-stereo.wav", "audio/raw", 44_100, 2),
            Fixture("wav-48000-24bit-stereo.wav", "audio/raw", 48_000, 2, PcmEncoding.PCM_24BIT_PACKED),
            Fixture("wav-44100-float-mono.wav", "audio/raw", 44_100, 1, PcmEncoding.PCM_FLOAT),
            Fixture("wav-11025-16bit-mono.wav", "audio/raw", 11_025, 1),
        ).map { arrayOf<Any>(it) }
    }

    private fun <T> withSource(block: (PcmSource) -> T): T {
        val file = TestFiles.copy("decoding/${fixture.name}")
        return try {
            TestFiles.open(file).use { pfd -> block(MediaCodecPcmSource(TestFiles.targetContext, pfd.fileDescriptor)) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun decodesAtTheSourceRateAndChannelCount() {
        val formats = mutableSetOf<PcmFormat>()
        var frames = 0L
        val info = withSource { source ->
            source.decode { pcm, format ->
                formats += format
                frames += pcm.remaining() / format.bytesPerFrame
                true
            }
        }

        assertThat(info.mimeType).isEqualTo(fixture.mimeType)
        assertThat(info.decoderName).isNotBlank()
        assertThat(formats).containsExactly(PcmFormat(fixture.sampleRate, fixture.channels, fixture.encoding))
        assertThat(frames.toDouble() / fixture.sampleRate)
            .isBetween(SECONDS - SLACK_SECONDS, SECONDS + fixture.endPaddingSeconds + SLACK_SECONDS)
    }

    @Test
    fun theFingerprintPathKeepsTheToneAtOneKilohertz() {
        val captured = mutableListOf<Short>()
        val capturing = object : FingerprintSink {
            override fun feed(samples: ShortArray, length: Int) {
                repeat(length) { captured += samples[it] }
            }

            override fun finish() = Fingerprint(IntArray(0), "")
            override fun close() {}
        }
        val calculator = FingerprintCalculator(NativeFingerprinting.resamplers, { capturing })

        val result = withSource { source -> runBlocking { calculator.fingerprint(source) } }

        assertThat(result.fedSeconds)
            .isBetween(SECONDS - SLACK_SECONDS, SECONDS + fixture.endPaddingSeconds + SLACK_SECONDS)
        val samples = captured.drop(Chromaprint.SAMPLE_RATE / 5).dropLast(Chromaprint.SAMPLE_RATE / 5)
        val tone = power(samples, 1_000.0)
        for (other in listOf(500.0, 2_000.0, 2_025.0, 3_000.0)) {
            assertThat(10 * log10(tone / power(samples, other))).describedAs("1 kHz over $other Hz").isGreaterThan(30.0)
        }
        // Half scale in every channel stays half scale after the downmix: an RMS of 0.5 / sqrt(2).
        assertThat(rms(samples) / 32_768).isCloseTo(0.3536, within(0.03))
    }

    /** Goertzel power of [frequency] at Chromaprint's rate. */
    private fun power(samples: List<Short>, frequency: Double): Double {
        val coefficient = 2 * cos(2 * PI * frequency / Chromaprint.SAMPLE_RATE)
        var previous = 0.0
        var beforePrevious = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - beforePrevious
            beforePrevious = previous
            previous = current
        }
        return abs(previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious)
    }

    private fun rms(samples: List<Short>) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
