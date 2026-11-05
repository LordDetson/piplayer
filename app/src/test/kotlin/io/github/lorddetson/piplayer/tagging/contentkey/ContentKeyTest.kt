package io.github.lorddetson.piplayer.tagging.contentkey

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The layout of key v1 (TI §3.1). Expected keys come from scripts/content_key_reference.py, never from this code. */
class ContentKeyTest {

    private fun key(format: KeyFormat, bytes: ByteArray) =
        ContentKey.hex(ContentKey.of(ArraySource(bytes), Payload.of(format, listOf(ByteSpan(0, bytes.size.toLong())))))

    @ParameterizedTest
    @CsvSource(
        "MP3, 1, 93116ad5ea2a26b01ea395b7909c391ad837100064c1aae055d14c9117bbd8a3",
        "ADTS, 65536, 6028e5fd4434465ba02fa821c7fc2fcdf52aeb399266f270002bdf14f192f63d",
        "FLAC, 131071, 9f2eb890a65e34c2adde3aa05f7d8f1e6d5f0da0fbc2e08fe96aaa1d4f836cae",
        "WAV, 131072, 94d49351d162d8f9c4b5e11213893a0e3839666adf962f1b3fa66230f22c752e",
        // One byte under the limit: hashed whole, once.
        "FLAC, 196607, 2195ab83432cd65a3627f10a02ced4d37fc0f851eedb98074f1e1b1b1fda2161",
        // At the limit: three windows of 64 KiB, which are the same bytes as the whole.
        "WAV, 196608, 0e95f658df3ae7d2cf855a4ff8d9bb23550414963493978a7a1ce43fbd29a67a",
        "FILE, 196609, c7a4b8e112f174a55f4f0025380a9822d9a00daeffcc9601c07a0944039f82f7",
        "MP4, 300000, a7d7711773206e63e6aa5f98bd82d13d27377b67e7cfe0fe3315564fce9f89ee",
    )
    fun `a payload hashes to its reference key`(format: KeyFormat, size: Int, expected: String) {
        assertThat(key(format, pattern(size))).isEqualTo(expected)
    }

    @Test
    fun `the length field is what the payload says, not its size, as an Ogg granule`() {
        val bytes = pattern(5_000)
        val span = listOf(ByteSpan(0, 5_000))
        val key = ContentKey.of(ArraySource(bytes), Payload(KeyFormat.OPUS, 960_312, span, emptyList()))

        assertThat(ContentKey.hex(key)).isEqualTo("bfaa60747bdd67c2dbefa676cccc1d0e4d7c19aa4f8cb19f51c3cf7c04721921")
    }

    @Test
    fun `the length field is a two's complement number`() {
        val key = ContentKey.of(ArraySource(pattern(10)), Payload(KeyFormat.OGG_VORBIS, -2, listOf(ByteSpan(0, 10)), emptyList()))

        assertThat(ContentKey.hex(key)).isEqualTo("cadf8cc76b5ca6eb5a4245f48b431a4653c717225b4087ea608f67b23d7bac20")
    }

    @Test
    fun `a key is 32 bytes`() {
        assertThat(ContentKey.of(ArraySource(pattern(10)), Payload.of(KeyFormat.MP3, listOf(ByteSpan(0, 10))))).hasSize(32)
    }

    @Test
    fun `bytes between the windows are not part of the key`() {
        val one = pattern(300_000)

        for (position in listOf(65_536, 117_231, 182_768, 234_463)) {
            val other = one.copyOf().also { it[position] = (it[position] + 1).toByte() }
            assertThat(key(KeyFormat.WAV, other)).describedAs("byte $position").isEqualTo(key(KeyFormat.WAV, one))
        }
    }

    @ParameterizedTest
    @CsvSource("0", "65535", "117232", "150000", "182767", "234464", "299999")
    fun `any byte of the three windows is part of the key`(position: Int) {
        val one = pattern(300_000)
        val other = one.copyOf().also { it[position] = (it[position] + 1).toByte() }

        assertThat(key(KeyFormat.WAV, other)).isNotEqualTo(key(KeyFormat.WAV, one))
    }

    @Test
    fun `different audio of one length between silent ends has different keys`() {
        val silence = ByteArray(100_000)
        val one = silence + pattern(200_000) + silence
        val other = silence + seeded(200_000, 5) + silence

        assertThat(key(KeyFormat.WAV, other)).isNotEqualTo(key(KeyFormat.WAV, one))
    }

    @Test
    fun `the middle window is found across gaps, rounding down`() {
        val spans = listOf(ByteSpan(0, 100_000), ByteSpan(150_000, 250_001))
        val payload = Payload.of(KeyFormat.MP4, spans)

        // (200 001 - 65 536) / 2 = 67 232 into the payload.
        assertThat(payload.middle).containsExactly(ByteSpan(67_232, 100_000), ByteSpan(150_000, 182_768))
    }

    @Test
    fun `a payload under 192 KiB has no windows`() {
        val payload = Payload.of(KeyFormat.WAV, listOf(ByteSpan(0, 196_607)))

        assertThat(payload.head).containsExactly(ByteSpan(0, 196_607))
        assertThat(payload.middle).isEmpty()
        assertThat(payload.tail).isEmpty()
    }

    @Test
    fun `the token is part of the key`() {
        assertThat(key(KeyFormat.WAV, pattern(100))).isNotEqualTo(key(KeyFormat.FLAC, pattern(100)))
    }

    @Test
    fun `spans make one payload across their gaps`() {
        val bytes = pattern(1_000)
        val split = Payload.of(KeyFormat.MP4, listOf(ByteSpan(0, 400), ByteSpan(500, 1_000)))
        val joined = bytes.copyOfRange(0, 400) + bytes.copyOfRange(500, 1_000)

        assertThat(split.length).isEqualTo(900)
        assertThat(ContentKey.hex(ContentKey.of(ArraySource(bytes), split))).isEqualTo(key(KeyFormat.MP4, joined))
    }

    @Test
    fun `the first and last bytes of spans cut across their gaps`() {
        val spans = listOf(ByteSpan(0, 40_000), ByteSpan(50_000, 90_000), ByteSpan(100_000, 140_000))

        assertThat(Payload.firstBytes(spans, 65_536)).containsExactly(ByteSpan(0, 40_000), ByteSpan(50_000, 75_536))
        assertThat(Payload.lastBytes(spans, 65_536)).containsExactly(ByteSpan(64_464, 90_000), ByteSpan(100_000, 140_000))
    }

    @Test
    fun `reading no more than the key needs`() {
        val source = ArraySource(pattern(5_000_000))

        ContentKey.of(source, Payload.of(KeyFormat.WAV, listOf(ByteSpan(0, 5_000_000))))

        assertThat(source.bytesRead).isEqualTo(196_608)
    }

    @Test
    fun `a file shorter than the payload it should hold is an error, not a shorter key`() {
        val payload = Payload.of(KeyFormat.WAV, listOf(ByteSpan(0, 200_000)))

        assertThatThrownBy { ContentKey.of(ArraySource(pattern(100_000)), payload) }.isInstanceOf(IOException::class.java)
    }
}
