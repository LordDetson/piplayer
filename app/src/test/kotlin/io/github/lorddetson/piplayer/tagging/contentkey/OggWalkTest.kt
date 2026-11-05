package io.github.lorddetson.piplayer.tagging.contentkey

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The bounded page walk of D10 (TI §3.1) on synthetic streams. */
class OggWalkTest {

    private val audio = pattern(400_000)

    private fun vorbis(comment: Int = 100, perPage: Int = 255, sound: ByteArray = audio, pageBody: Int = 4_000) =
        bytesOf(Ogg.vorbisHeaders(comment, perPage), Ogg.audioPages(sound, pageBody))

    private fun opus(comment: Int = 100, perPage: Int = 255, sound: ByteArray = audio) =
        bytesOf(Ogg.opusHeaders(comment, perPage), Ogg.audioPages(sound))

    private fun vorbisKey(file: ByteArray) = keyOf(file, AudioContainer.OGG)

    @Test
    fun `the key takes the granule and the first and last 64 KiB of the page bodies`() {
        val (key, located) = vorbisKey(vorbis())

        assertThat(located.payload.format).isEqualTo(KeyFormat.OGG_VORBIS)
        assertThat(located.payload.length).isEqualTo(960_000)
        val whole = bytesOf(audio.copyOfRange(0, 65_536), audio.copyOfRange(audio.size - 65_536, audio.size))
        val expected = ContentKey.of(
            ArraySource(whole),
            Payload(KeyFormat.OGG_VORBIS, 960_000, listOf(ByteSpan(0, 65_536)), listOf(ByteSpan(65_536, 131_072))),
        )
        assertThat(key).isEqualTo(ContentKey.hex(expected))
    }

    @Test
    fun `Ogg has no middle window, so that its walk stays bounded`() {
        assertThat(vorbisKey(vorbis()).second.payload.middle).isEmpty()
    }

    @Test
    fun `Opus takes its own token and two header packets`() {
        val (_, located) = keyOf(opus(), AudioContainer.OGG)

        assertThat(located.payload.format).isEqualTo(KeyFormat.OPUS)
        assertThat(located.payload.length).isEqualTo(960_000)
        assertThat(located.ogg).isEqualTo(OggLength(960_000, 48_000, 312))
    }

    @Test
    fun `Vorbis reports its sample rate and no pre-skip`() {
        assertThat(vorbisKey(vorbis()).second.ogg).isEqualTo(OggLength(960_000, 44_100, 0))
    }

    @Test
    fun `rewriting the comments leaves the key alone, across page boundaries and in any number of pages`() {
        val plain = vorbisKey(vorbis()).first

        assertThat(vorbisKey(vorbis(comment = 20)).first).isEqualTo(plain)
        assertThat(vorbisKey(vorbis(comment = 100_000)).first).isEqualTo(plain)
        assertThat(vorbisKey(vorbis(comment = 100, perPage = 3)).first).isEqualTo(plain)
        assertThat(keyOf(opus(comment = 100_000), AudioContainer.OGG).first)
            .isEqualTo(keyOf(opus(comment = 20), AudioContainer.OGG).first)
    }

    @Test
    fun `audio pages renumbered after a rewrite leave the key alone`() {
        val plain = vorbisKey(vorbis()).first
        val renumbered = Ogg.vorbisHeaders() + audio.toList().chunked(4_000).mapIndexed { index, chunk ->
            val granule = 960_000L * (index + 1) / (audio.size / 4_000)
            Ogg.page(chunk.toByteArray(), Ogg.lacingOf(chunk.size), granule = granule, sequence = 1_000L + index)
        }.fold(ByteArray(0)) { all, page -> all + page }

        assertThat(vorbisKey(renumbered).first).isEqualTo(plain)
    }

    @Test
    fun `audio in other pages leaves the key alone`() {
        assertThat(vorbisKey(vorbis(pageBody = 9_000)).first).isEqualTo(vorbisKey(vorbis(pageBody = 4_000)).first)
    }

    @ParameterizedTest
    @CsvSource("10", "65535", "334464", "399999")
    fun `a byte of the first or last 64 KiB changing changes the key`(position: Int) {
        val changed = audio.copyOf().also { it[position] = (it[position] + 1).toByte() }

        assertThat(vorbisKey(vorbis(sound = changed)).first).isNotEqualTo(vorbisKey(vorbis()).first)
    }

    @Test
    fun `another final granule changes the key`() {
        val other = bytesOf(Ogg.vorbisHeaders(), Ogg.audioPages(audio, finalGranule = 960_001))

        assertThat(vorbisKey(other).first).isNotEqualTo(vorbisKey(vorbis()).first)
    }

    @Test
    fun `the walk reads no header page bodies and little more than the key`() {
        val withCover = ArraySource(vorbis(comment = 3_000_000))
        val located = PayloadLocator.locate(withCover, AudioContainer.OGG, null)
        ContentKey.of(withCover, located.payload)

        // 128 KiB of hashing, the first read of the tail, and page headers.
        assertThat(withCover.bytesRead).isLessThan(131_072L + 81_920 + 16_384)
    }

    @ParameterizedTest
    @CsvSource("1", "65536", "131071")
    fun `a payload under 128 KiB is hashed whole, once, up to its last page`(size: Int) {
        val sound = pattern(size)
        val (key, located) = vorbisKey(vorbis(sound = sound))

        assertThat(located.payload.tail).isEmpty()
        assertThat(located.payload.head.sumOf { it.size }).isEqualTo(size.toLong())
        val expected = ContentKey.of(ArraySource(sound), Payload(KeyFormat.OGG_VORBIS, 960_000, listOf(ByteSpan(0, size.toLong())), emptyList()))
        assertThat(key).isEqualTo(ContentKey.hex(expected))
    }

    @Test
    fun `a payload of exactly 128 KiB is read as first and last 64 KiB`() {
        val sound = pattern(131_072)
        val (key, located) = vorbisKey(vorbis(sound = sound))

        assertThat(located.payload.head.sumOf { it.size }).isEqualTo(65_536)
        assertThat(located.payload.tail.sumOf { it.size }).isEqualTo(65_536)
        val expected = ContentKey.of(ArraySource(sound), Payload(KeyFormat.OGG_VORBIS, 960_000, listOf(ByteSpan(0, 131_072)), emptyList()))
        assertThat(key).isEqualTo(ContentKey.hex(expected))
    }

    @Test
    fun `a short payload is told by its page bodies, not by the size of its pages`() {
        // 130 000 bytes of bodies in pages of 100 bytes take 170 KiB of file, far over 128 KiB.
        val sound = pattern(130_000)
        val (_, located) = vorbisKey(vorbis(sound = sound, pageBody = 100))

        assertThat(located.payload.tail).isEmpty()
        assertThat(located.payload.head.sumOf { it.size }).isEqualTo(130_000)
    }

    @Test
    fun `pages with little body still give the last 64 KiB, whatever the window`() {
        val small = vorbisKey(vorbis(pageBody = 30))
        val large = vorbisKey(vorbis(pageBody = 4_000))

        assertThat(small.first).isEqualTo(large.first)
        assertThat(small.second.payload.tail.sumOf { it.size }).isEqualTo(65_536)
    }

    @Test
    fun `pages near the largest size still give the last 64 KiB`() {
        val sound = pattern(800_000)

        assertThat(vorbisKey(vorbis(sound = sound, pageBody = 65_000)).first)
            .isEqualTo(vorbisKey(vorbis(sound = sound, pageBody = 4_000)).first)
    }

    @Test
    fun `the capture pattern inside a body is not a page start`() {
        val sound = audio.copyOf()
        val fake = Ogg.page(ByteArray(0), emptyList(), granule = 5, goodCrc = false)
        for (at in sound.size - 90_000 until sound.size - 1_000 step 700) fake.copyInto(sound, at)

        val (_, located) = vorbisKey(vorbis(sound = sound))

        assertThat(located.payload.tail.sumOf { it.size }).isEqualTo(65_536)
        assertThat(located.payload.length).isEqualTo(960_000)
    }

    @Test
    fun `a last page cut off does not count, its granule neither`() {
        val whole = vorbis()
        val cut = whole.copyOf(whole.size - 1_000)

        val (_, located) = vorbisKey(cut)

        assertThat(located.payload.format).isEqualTo(KeyFormat.OGG_VORBIS)
        val pages = audio.size / 4_000
        assertThat(located.payload.length).isEqualTo(960_000L * (pages - 1) / pages)
    }

    @Test
    fun `tags or junk after the last page leave the key alone`() {
        val plain = vorbisKey(vorbis()).first

        assertThat(vorbisKey(bytesOf(vorbis(), id3v1Tag())).first).isEqualTo(plain)
        assertThat(vorbisKey(bytesOf(vorbis(), apeTag(5_000))).first).isEqualTo(plain)
    }

    @Test
    fun `a last page without a granule takes the granule of the page before`() {
        val file = bytesOf(vorbis(), Ogg.page(pattern(50), listOf(50), granule = -1))

        assertThat(vorbisKey(file).second.payload.length).isEqualTo(960_000)
    }

    @Test
    fun `a bad page near the end is skipped, the same way whatever comes after it`() {
        val pages = Ogg.audioPages(audio)
        val brokenAt = pages.size - 10_000
        val broken = pages.copyOf().also { it[brokenAt] = (it[brokenAt] + 1).toByte() }

        val (_, located) = vorbisKey(bytesOf(Ogg.vorbisHeaders(), broken))

        assertThat(located.payload.format).isEqualTo(KeyFormat.OGG_VORBIS)
        assertThat(located.payload.tail.sumOf { it.size }).isEqualTo(65_536)
    }

    @Test
    fun `a second logical stream at the end takes the whole file`() {
        val chained = bytesOf(vorbis(), Ogg.vorbisHeaders(), Ogg.audioPages(pattern(5_000), serial = 99))

        assertThat(vorbisKey(chained).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Test
    fun `a chain of a short stream takes the whole file`() {
        val link = Ogg.page(Ogg.vorbisIdentification, Ogg.lacingOf(Ogg.vorbisIdentification.size), flags = Ogg.BOS, serial = 99)
        val chained = bytesOf(vorbis(sound = pattern(5_000)), link)

        assertThat(vorbisKey(chained).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Test
    fun `a second beginning of stream at the start takes the whole file`() {
        val theora = Ogg.page(pattern(42), listOf(42), flags = Ogg.BOS, serial = 99)
        val first = Ogg.page(Ogg.vorbisIdentification, Ogg.lacingOf(Ogg.vorbisIdentification.size), flags = Ogg.BOS)
        val rest = Ogg.pages(listOf(Ogg.comment(100), Ogg.setup), 255).fold(ByteArray(0)) { all, page -> all + page }
        val multiplexed = bytesOf(first, theora, rest, Ogg.audioPages(audio))

        assertThat(vorbisKey(multiplexed).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Test
    fun `no audio pages, too few headers or another codec takes the whole file`() {
        val noAudio = Ogg.vorbisHeaders()
        val fewHeaders = Ogg.page(Ogg.vorbisIdentification, Ogg.lacingOf(Ogg.vorbisIdentification.size), flags = Ogg.BOS)
        val speex = bytesOf(Ogg.page(bytesOf("Speex   ", ByteArray(72)), listOf(80), flags = Ogg.BOS), Ogg.audioPages(audio))

        assertThat(vorbisKey(noAudio).second.payload.format).isEqualTo(KeyFormat.FILE)
        assertThat(vorbisKey(fewHeaders).second.payload.format).isEqualTo(KeyFormat.FILE)
        assertThat(vorbisKey(speex).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Test
    fun `a stream whose pages all lack a granule takes the whole file`() {
        val noGranule = bytesOf(Ogg.vorbisHeaders(), Ogg.page(pattern(50), listOf(50), granule = -1))

        assertThat(vorbisKey(noGranule).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Test
    fun `huge junk after the last page gives up and takes the whole file`() {
        val junk = ByteArray(3_000_000) { 0x41 }

        assertThat(vorbisKey(bytesOf(vorbis(), junk)).second.payload.format).isEqualTo(KeyFormat.FILE)
    }
}
