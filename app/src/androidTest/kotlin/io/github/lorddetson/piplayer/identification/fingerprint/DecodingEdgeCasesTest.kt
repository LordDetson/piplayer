package io.github.lorddetson.piplayer.identification.fingerprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.junit.runner.RunWith

/** Files that end, change or encode their audio in unusual ways (scripts/generate_decoding_fixtures.py). */
@RunWith(AndroidJUnit4::class)
class DecodingEdgeCasesTest {

    private class Decoded(val info: SourceInfo, val formats: List<PcmFormat>, val seconds: Double)

    private fun <T> withSource(name: String, block: (PcmSource) -> T): T {
        val file = TestFiles.copy("decoding/$name")
        return try {
            TestFiles.open(file).use { pfd -> block(MediaCodecPcmSource(TestFiles.targetContext, pfd.fileDescriptor)) }
        } finally {
            file.delete()
        }
    }

    private fun decode(name: String): Decoded = withSource(name) { source ->
        val formats = mutableListOf<PcmFormat>()
        var seconds = 0.0
        val info = source.decode { pcm, format ->
            if (formats.lastOrNull() != format) formats += format
            seconds += (pcm.remaining() / format.bytesPerFrame).toDouble() / format.sampleRate
            true
        }
        Decoded(info, formats, seconds)
    }

    @Test
    fun aSampleRateThatRisesInsideAnMp3ReachesTheConsumer() {
        val decoded = decode("mp3-44100-then-48000-stereo.mp3")

        assertThat(decoded.formats.map { it.sampleRate }).containsExactly(44_100, 48_000)
        // Two halves of a second each, plus the encoder delay and padding nobody trims without a Xing frame.
        assertThat(decoded.seconds).isCloseTo(2.0, within(0.15))
    }

    @Test
    fun aFlacCutOffInItsLastFrameKeepsWhatWasDecoded() {
        val decoded = decode("flac-44100-16bit-mono-truncated.flac")

        assertThat(decoded.seconds).isBetween(1.85, 2.0)
        val result = withSource("flac-44100-16bit-mono-truncated.flac") { source ->
            runBlocking { NativeFingerprinting.calculator().fingerprint(source) }
        }
        // Under 2 s of audio is too short for a fingerprint item (Chromaprint needs about 2.7 s), but it was fed.
        assertThat(result.fedSeconds).isGreaterThan(1.8)
        // Its length is exact only when the decoder took the cut frame without an error.
        assertThat(result.reachedEnd).isEqualTo(result.source.endedEarly == null)
    }

    @Test
    fun aFlacOfASingleFrameIsDecodedOnce() {
        val decoded = decode("flac-44100-16bit-mono-one-frame.flac")

        assertThat(decoded.seconds).isCloseTo(0.05, within(0.001))
    }

    @Test
    fun aWavOfSixtyFourBitFloatsIsRefusedInsteadOfReadAsNoise() {
        assertThatThrownBy { decode("wav-44100-double-mono.wav") }
            .isInstanceOf(DecodingException.UnsupportedFormat::class.java)
    }
}
