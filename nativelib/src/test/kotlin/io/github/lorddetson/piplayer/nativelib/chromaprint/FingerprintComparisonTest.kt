package io.github.lorddetson.piplayer.nativelib.chromaprint

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class FingerprintComparisonTest {

    private val random = Random(7)
    private val print = IntArray(200) { random.nextInt() }

    @Test
    fun `identical fingerprints match at offset zero without errors`() {
        assertThat(FingerprintComparison.bestMatch(print, print.copyOf()))
            .isEqualTo(FingerprintMatch(offset = 0, bitErrorRate = 0.0, overlap = 200))
    }

    @Test
    fun `the bit error rate counts differing bits over the overlap`() {
        val changed = print.copyOf()
        changed[10] = changed[10] xor 0b1011 // three bits
        changed[50] = changed[50] xor (1 shl 31) // one bit
        val match = FingerprintComparison.bestMatch(print, changed)!!
        assertThat(match.offset).isEqualTo(0)
        assertThat(match.bitErrorRate).isCloseTo(4.0 / (32 * 200), within(1e-12))
    }

    @Test
    fun `a fingerprint that starts later is found at its offset`() {
        val later = print.copyOfRange(25, print.size)
        val match = FingerprintComparison.bestMatch(print, later)!!
        assertThat(match.offset).isEqualTo(25)
        assertThat(match.bitErrorRate).isEqualTo(0.0)
        assertThat(match.overlap).isEqualTo(175)
    }

    @Test
    fun `a fingerprint that starts earlier is found at a negative offset`() {
        val earlier = IntArray(10) { random.nextInt() } + print
        val match = FingerprintComparison.bestMatch(print, earlier)!!
        assertThat(match.offset).isEqualTo(-10)
        assertThat(match.bitErrorRate).isEqualTo(0.0)
    }

    @Test
    fun `unrelated fingerprints differ in about half of their bits`() {
        val other = IntArray(200) { random.nextInt() }
        assertThat(FingerprintComparison.bestMatch(print, other)!!.bitErrorRate).isBetween(0.4, 0.6)
    }

    @Test
    fun `offsets beyond the window are not searched`() {
        val later = print.copyOfRange(100, print.size)
        val match = FingerprintComparison.bestMatch(print, later, maxOffset = 50)
        assertThat(match?.bitErrorRate ?: 1.0).isGreaterThan(0.3)
    }

    @Test
    fun `fingerprints too short to overlap by half give no match`() {
        assertThat(FingerprintComparison.bestMatch(IntArray(0), print)).isNull()
        assertThat(FingerprintComparison.bestMatch(print, print.copyOfRange(0, 1))).isNull()
    }
}
