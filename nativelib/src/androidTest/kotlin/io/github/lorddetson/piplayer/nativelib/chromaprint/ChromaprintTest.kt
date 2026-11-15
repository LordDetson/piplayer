package io.github.lorddetson.piplayer.nativelib.chromaprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChromaprintTest {

    /** 30 seconds of chords and noise at 11025 Hz: enough variety for a meaningful fingerprint. */
    private val pcm: ShortArray = run {
        val random = Random(3)
        ShortArray(30 * Chromaprint.SAMPLE_RATE) { i ->
            val t = i.toDouble() / Chromaprint.SAMPLE_RATE
            val chord = (i / Chromaprint.SAMPLE_RATE) % 4
            val tone = sin(2 * PI * (220.0 + 55 * chord) * t) + sin(2 * PI * (330.0 + 44 * chord) * t)
            (tone * 6000 + random.nextInt(-1500, 1500)).toInt().toShort()
        }
    }

    @Test
    fun aFingerprintHasItemsAndTheEncodedFormForAlgorithmTwo() {
        val fingerprint = Chromaprint.fingerprint(pcm)
        // About one item per 1365 samples after a start-up delay of 28666 samples (fpcalc: 948 items for 120 s).
        val expected = (pcm.size - 28_666) / 1365
        assertThat(fingerprint.raw.size).isBetween(expected - 1, expected + 1)
        // The compressed form starts with the algorithm number 1 (fpcalc's "algorithm 2"), "AQ" in base64.
        assertThat(fingerprint.encoded).startsWith("AQ")
    }

    @Test
    fun theSamePcmInPiecesOfAnySizeGivesTheSameFingerprint() {
        val whole = Chromaprint.fingerprint(pcm).raw
        for (piece in listOf(1, 997, 4096)) {
            val pieces = ChromaprintSession().use { session ->
                var start = 0
                while (start < pcm.size) {
                    val length = minOf(piece, pcm.size - start)
                    session.feed(pcm, start, length)
                    start += length
                }
                session.finish().raw
            }
            assertThat(pieces).describedAs("pieces of $piece").isEqualTo(whole)
        }
    }

    @Test
    fun encodingTheRawItemsGivesTheSessionsEncodedString() {
        // What the fingerprint cache keeps (raw items) must give AcoustID the same string as the session did (TI §3.2).
        for (seconds in listOf(4, 30)) {
            val fingerprint = Chromaprint.fingerprint(pcm.copyOf(seconds * Chromaprint.SAMPLE_RATE))
            assertThat(fingerprint.raw).describedAs("$seconds s").isNotEmpty()
            assertThat(Chromaprint.encode(fingerprint.raw)).describedAs("$seconds s").isEqualTo(fingerprint.encoded)
        }
    }

    @Test
    fun encodingNoItemsGivesAnEmptyStringLikeTheSession() {
        // Under about 2.7 s of audio the session has no items and gives an empty string.
        val short = Chromaprint.fingerprint(pcm.copyOf(Chromaprint.SAMPLE_RATE))
        assertThat(short.raw).isEmpty()
        assertThat(short.encoded).isEmpty()
        assertThat(Chromaprint.encode(IntArray(0))).isEmpty()
    }

    @Test
    fun theEncodedStringNamesTheAlgorithmAndUsesUrlSafeBase64WithoutPadding() {
        val raw = intArrayOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x12345678)
        // The first byte is the algorithm: 1 (fpcalc's "algorithm 2") is "AQ" in base64, 2 is "Ag".
        assertThat(Chromaprint.encode(raw)).startsWith("AQ").doesNotContain("=", "+", "/")
        assertThat(Chromaprint.encode(raw, algorithm = 2)).startsWith("Ag")
    }

    @Test
    fun encodingWithAnUnknownAlgorithmIsRefused() {
        assertThatThrownBy { Chromaprint.encode(intArrayOf(1, 2, 3), algorithm = 99) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun anyOtherSampleRateIsRefused() {
        assertThatThrownBy { ChromaprintSession(sampleRate = 44_100) }.isInstanceOf(ChromaprintException::class.java)
    }

    @Test
    fun noChannelsIsRefused() {
        assertThatThrownBy { ChromaprintSession(channels = 0) }.isInstanceOf(ChromaprintException::class.java)
    }

    @Test
    fun aFinishedSessionTakesNoMoreInput() {
        ChromaprintSession().use { session ->
            session.feed(pcm)
            session.finish()
            assertThatThrownBy { session.feed(pcm) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun aClosedSessionTakesNoMoreInput() {
        val session = ChromaprintSession()
        session.close()
        assertThatThrownBy { session.feed(pcm) }.isInstanceOf(IllegalStateException::class.java)
        session.close() // closing twice is harmless
    }

    @Test
    fun aRangeOutsideTheArrayIsRefused() {
        ChromaprintSession().use { session ->
            assertThatThrownBy { session.feed(pcm, pcm.size - 10, 20) }.isInstanceOf(IndexOutOfBoundsException::class.java)
        }
    }
}
