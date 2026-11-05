package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.FrameCountHeader
import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Where the audio of each format is, on synthetic files (TI §3.1). */
class PayloadLocatorTest {

    private val audio = pattern(200_000)
    private val shortAudio = pattern(3_000)

    private fun spans(located: Located) = located.payload.head + located.payload.tail

    @Nested
    inner class Mp3 {
        private val frames = shortAudio

        /** A layout as TagLib would report it for [frames] placed after [before] bytes. */
        private fun layout(before: Int, total: Int, lastLength: Int = 100, service: Boolean = false, audioEnd: Int = total) = MpegLayout(
            adts = false,
            firstFrame = before.toLong(),
            firstFrameLength = 200,
            serviceFrame = service,
            lastFrame = (before + frames.size - 100).toLong(),
            lastFrameLength = lastLength,
            audioEnd = audioEnd.toLong(),
            frameCount = if (service) FrameCountHeader(false, 10, 3_000) else null,
            firstFrameBitrateKbps = 128,
        )

        @Test
        fun `the payload runs from the first frame to the end of the last`() {
            val file = bytesOf(id3v2Tag(500), frames)
            val (_, located) = keyOf(file, AudioContainer.MPEG, layout(510, file.size))

            assertThat(located.payload.format).isEqualTo(KeyFormat.MP3)
            assertThat(located.payload.length).isEqualTo(3_000)
            assertThat(spans(located)).containsExactly(ByteSpan(510, 3_510))
        }

        @Test
        fun `only tags changing leaves the key alone`() {
            val plain = keyOf(frames, AudioContainer.MPEG, layout(0, frames.size)).first

            val tagged = bytesOf(id3v2Tag(4_000), frames, apeTag(), id3v1Tag())
            val end = 4_010 + frames.size
            val key = keyOf(tagged, AudioContainer.MPEG, layout(4_010, tagged.size, audioEnd = end)).first

            assertThat(key).isEqualTo(plain)
        }

        @Test
        fun `a payload byte changing changes the key`() {
            val plain = keyOf(frames, AudioContainer.MPEG, layout(0, frames.size)).first

            val changed = frames.copyOf().also { it[1_500] = (it[1_500] + 1).toByte() }

            assertThat(keyOf(changed, AudioContainer.MPEG, layout(0, frames.size)).first).isNotEqualTo(plain)
        }

        @Test
        fun `a service frame is not part of the payload`() {
            val (_, located) = keyOf(frames, AudioContainer.MPEG, layout(0, frames.size, service = true))

            assertThat(spans(located)).containsExactly(ByteSpan(200, 3_000))
            assertThat(located.payload.length).isEqualTo(2_800)
        }

        @Test
        fun `rebuilding the service frame leaves the key alone`() {
            val other = frames.copyOf().also { for (i in 0 until 200) it[i] = 0x55 }

            assertThat(keyOf(other, AudioContainer.MPEG, layout(0, frames.size, service = true)).first)
                .isEqualTo(keyOf(frames, AudioContainer.MPEG, layout(0, frames.size, service = true)).first)
        }

        @Test
        fun `a last frame cut off by the end of the file stops at the end`() {
            val (_, located) = keyOf(frames, AudioContainer.MPEG, layout(0, frames.size, lastLength = 900))

            assertThat(spans(located)).containsExactly(ByteSpan(0, 3_000))
        }

        @Test
        fun `a last frame cut off and followed by a tag stops at the tag`() {
            val file = bytesOf(frames, id3v1Tag())
            val (_, located) = keyOf(file, AudioContainer.MPEG, layout(0, file.size, lastLength = 900, audioEnd = 3_000))

            assertThat(spans(located)).containsExactly(ByteSpan(0, 3_000))
        }

        @Test
        fun `no frames found takes the whole file`() {
            val file = bytesOf(frames)
            val none = layout(0, file.size).copy(firstFrame = -1, lastFrame = -1)

            val (_, located) = keyOf(file, AudioContainer.MPEG, none)

            assertThat(located.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(located.payload.length).isEqualTo(file.size.toLong())
        }

        @Test
        fun `a last frame before the first takes the whole file`() {
            val file = bytesOf(frames)
            val odd = layout(2_000, file.size).copy(lastFrame = 100)

            assertThat(keyOf(file, AudioContainer.MPEG, odd).second.payload.format).isEqualTo(KeyFormat.FILE)
        }

        @Test
        fun `nothing but a service frame takes the whole file`() {
            val file = bytesOf(frames)
            val odd = layout(0, file.size, service = true).copy(firstFrameLength = 3_000)

            assertThat(keyOf(file, AudioContainer.MPEG, odd).second.payload.format).isEqualTo(KeyFormat.FILE)
        }

        @Test
        fun `an MPEG file without a layout takes the whole file`() {
            assertThat(keyOf(frames, AudioContainer.MPEG, null).second.payload.format).isEqualTo(KeyFormat.FILE)
        }
    }

    @Nested
    inner class Adts {
        private fun layout(first: Int, end: Int) = MpegLayout(
            adts = true,
            firstFrame = first.toLong(),
            firstFrameLength = 200,
            serviceFrame = false,
            lastFrame = (end - 200).toLong(),
            lastFrameLength = 100,
            audioEnd = end.toLong(),
            frameCount = null,
            firstFrameBitrateKbps = 64,
        )

        @Test
        fun `the payload is the file without its tags`() {
            val file = bytesOf(id3v2Tag(1_000), shortAudio, id3v1Tag())
            val (_, located) = keyOf(file, AudioContainer.MPEG, layout(1_010, 4_010))

            assertThat(located.payload.format).isEqualTo(KeyFormat.ADTS)
            assertThat(spans(located)).containsExactly(ByteSpan(1_010, 4_010))
        }

        @Test
        fun `tags changing leave the key alone`() {
            val plain = keyOf(shortAudio, AudioContainer.MPEG, layout(0, 3_000)).first
            val file = bytesOf(id3v2Tag(1_000), shortAudio, apeTag(), id3v1Tag())

            assertThat(keyOf(file, AudioContainer.MPEG, layout(1_010, 4_010)).first).isEqualTo(plain)
        }
    }

    @Nested
    inner class Flac {
        private fun block(type: Int, last: Boolean, size: Int) =
            bytesOf(type or (if (last) 0x80 else 0), bigEndian(size.toLong(), 3), ByteArray(size))

        private val streamInfo = block(0, false, 34)

        private fun file(blocks: ByteArray, frames: ByteArray = audio) = bytesOf("fLaC", streamInfo, blocks, frames)

        @Test
        fun `the payload is the frames after the last metadata block`() {
            val file = file(block(4, true, 100))
            val (_, located) = keyOf(file, AudioContainer.FLAC)

            assertThat(located.payload.format).isEqualTo(KeyFormat.FLAC)
            assertThat(located.payload.length).isEqualTo(200_000)
            assertThat(located.payload.head.first().start).isEqualTo(file.size - 200_000L)
        }

        @Test
        fun `metadata blocks changing leave the key alone`() {
            val plain = keyOf(file(block(4, true, 100)), AudioContainer.FLAC).first
            val more = bytesOf(block(4, false, 20_000), block(6, false, 300_000), block(1, true, 8_192))

            assertThat(keyOf(file(more), AudioContainer.FLAC).first).isEqualTo(plain)
        }

        @Test
        fun `a leading ID3v2 tag and trailing ID3v1 and APE tags stay outside`() {
            val plain = keyOf(file(block(4, true, 100)), AudioContainer.FLAC).first
            val tagged = bytesOf(id3v2Tag(5_000), file(block(4, true, 100)), apeTag(), id3v1Tag())

            assertThat(keyOf(tagged, AudioContainer.FLAC).first).isEqualTo(plain)
            assertThat(keyOf(bytesOf(file(block(4, true, 100)), id3v1Tag()), AudioContainer.FLAC).first).isEqualTo(plain)
            assertThat(keyOf(bytesOf(file(block(4, true, 100)), apeTag()), AudioContainer.FLAC).first).isEqualTo(plain)
        }

        @Test
        fun `the end of a short APE tag that reads like ID3v1 is not an ID3v1 tag`() {
            val plain = keyOf(file(block(4, true, 100)), AudioContainer.FLAC).first
            // An item of 67 bytes makes the tag 131 bytes long: the TAG in its APETAGEX stands 128 bytes before its end.
            val ape = apeTag(67)

            assertThat(keyOf(bytesOf(file(block(4, true, 100)), ape), AudioContainer.FLAC).first).isEqualTo(plain)
        }

        @Test
        fun `a frame byte changing changes the key`() {
            val changed = audio.copyOf().also { it[10] = (it[10] + 1).toByte() }

            assertThat(keyOf(file(block(4, true, 100), changed), AudioContainer.FLAC).first)
                .isNotEqualTo(keyOf(file(block(4, true, 100)), AudioContainer.FLAC).first)
        }

        @Test
        fun `no stream marker or no frames takes the whole file`() {
            val noMarker = bytesOf("nope", streamInfo, block(4, true, 100), audio)
            val noFrames = file(block(4, true, 100), ByteArray(0))
            val unfinished = bytesOf("fLaC", streamInfo)

            assertThat(keyOf(noMarker, AudioContainer.FLAC).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(noFrames, AudioContainer.FLAC).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(unfinished, AudioContainer.FLAC).second.payload.format).isEqualTo(KeyFormat.FILE)
        }
    }

    @Nested
    inner class Mp4 {
        private fun atom(type: String, content: ByteArray) = bytesOf(bigEndian(content.size + 8L, 4), type, content)

        private val ftyp = atom("ftyp", ByteArray(16))
        private val moov = atom("moov", ByteArray(2_000))

        @Test
        fun `the payload is the mdat contents, with moov before or after`() {
            val before = keyOf(bytesOf(ftyp, moov, atom("mdat", audio)), AudioContainer.MP4)
            val after = keyOf(bytesOf(ftyp, atom("mdat", audio), moov), AudioContainer.MP4)

            assertThat(before.second.payload.format).isEqualTo(KeyFormat.MP4)
            assertThat(before.second.payload.length).isEqualTo(200_000)
            assertThat(after.first).isEqualTo(before.first)
        }

        @Test
        fun `a bigger moov and free space leave the key alone`() {
            val plain = keyOf(bytesOf(ftyp, moov, atom("mdat", audio)), AudioContainer.MP4).first
            val other = bytesOf(ftyp, atom("moov", ByteArray(90_000)), atom("free", ByteArray(4_000)), atom("mdat", audio))

            assertThat(keyOf(other, AudioContainer.MP4).first).isEqualTo(plain)
        }

        @Test
        fun `several mdat atoms make one payload in file order`() {
            val first = audio.copyOfRange(0, 150_000)
            val second = audio.copyOfRange(150_000, 200_000)
            val plain = keyOf(bytesOf(ftyp, atom("mdat", audio), moov), AudioContainer.MP4).first
            val split = bytesOf(ftyp, atom("mdat", first), atom("free", ByteArray(99)), atom("mdat", second), moov)

            val (key, located) = keyOf(split, AudioContainer.MP4)

            assertThat(key).isEqualTo(plain)
            assertThat(located.payload.length).isEqualTo(200_000)
        }

        @Test
        fun `an atom with a 64-bit size`() {
            val plain = keyOf(bytesOf(ftyp, atom("mdat", audio), moov), AudioContainer.MP4).first
            val large = bytesOf(ftyp, bigEndian(1, 4), "mdat", bigEndian(audio.size + 16L, 8), audio, moov)

            assertThat(keyOf(large, AudioContainer.MP4).first).isEqualTo(plain)
        }

        @Test
        fun `an mdat of size 0 runs to the end of the file`() {
            val plain = keyOf(bytesOf(ftyp, moov, atom("mdat", audio)), AudioContainer.MP4).first
            val open = bytesOf(ftyp, moov, bigEndian(0, 4), "mdat", audio)

            val (key, located) = keyOf(open, AudioContainer.MP4)

            assertThat(key).isEqualTo(plain)
            assertThat(located.declaredBeyondEnd).isFalse()
        }

        @Test
        fun `an mdat longer than the file is cut and reported`() {
            val cut = bytesOf(ftyp, moov, bigEndian(500_000, 4), "mdat", audio)

            val (_, located) = keyOf(cut, AudioContainer.MP4)

            assertThat(located.payload.length).isEqualTo(200_000)
            assertThat(located.declaredBeyondEnd).isTrue()
        }

        @Test
        fun `a whole moov after a whole mdat is not reported as cut`() {
            assertThat(keyOf(bytesOf(ftyp, atom("mdat", audio), moov), AudioContainer.MP4).second.declaredBeyondEnd).isFalse()
        }

        @Test
        fun `no mdat, an empty mdat or an impossible atom size takes the whole file`() {
            val none = bytesOf(ftyp, moov)
            val empty = bytesOf(ftyp, atom("mdat", ByteArray(0)), moov)
            val broken = bytesOf(ftyp, bigEndian(4, 4), "mdat", audio)

            assertThat(keyOf(none, AudioContainer.MP4).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(empty, AudioContainer.MP4).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(broken, AudioContainer.MP4).second.payload.format).isEqualTo(KeyFormat.FILE)
        }

        @Test
        fun `a tag at the end of the file stops the walk without harm`() {
            val plain = keyOf(bytesOf(ftyp, moov, atom("mdat", audio)), AudioContainer.MP4)
            val tagged = keyOf(bytesOf(ftyp, moov, atom("mdat", audio), id3v1Tag()), AudioContainer.MP4)

            assertThat(tagged.first).isEqualTo(plain.first)
            assertThat(tagged.second.declaredBeyondEnd).isFalse()
        }
    }

    @Nested
    inner class Wav {
        private fun chunk(id: String, content: ByteArray) =
            bytesOf(id, littleEndian(content.size.toLong(), 4), content, if (content.size % 2 == 1) ByteArray(1) else ByteArray(0))

        private fun riff(vararg chunks: ByteArray): ByteArray {
            val body = chunks.fold(ByteArray(0)) { all, next -> all + next }
            return bytesOf("RIFF", littleEndian(body.size + 4L, 4), "WAVE", body)
        }

        private val fmt = chunk("fmt ", ByteArray(16))

        @Test
        fun `the payload is the data chunk`() {
            val (_, located) = keyOf(riff(fmt, chunk("data", audio)), AudioContainer.WAV)

            assertThat(located.payload.format).isEqualTo(KeyFormat.WAV)
            assertThat(located.payload.length).isEqualTo(200_000)
            assertThat(located.declaredBeyondEnd).isFalse()
        }

        @Test
        fun `chunks around data leave the key alone, odd sizes padded`() {
            val plain = keyOf(riff(fmt, chunk("data", audio)), AudioContainer.WAV).first
            val other = riff(chunk("LIST", ByteArray(1_001)), fmt, chunk("id3 ", ByteArray(333)), chunk("data", audio), chunk("LIST", ByteArray(50)))

            assertThat(keyOf(other, AudioContainer.WAV).first).isEqualTo(plain)
        }

        @Test
        fun `a data chunk longer than the file is cut and reported`() {
            val cut = bytesOf("RIFF", littleEndian(0xFFFFFFFFL, 4), "WAVE", fmt, "data", littleEndian(0xFFFFFFFFL, 4), audio)

            val (_, located) = keyOf(cut, AudioContainer.WAV)

            assertThat(located.payload.length).isEqualTo(200_000)
            assertThat(located.declaredBeyondEnd).isTrue()
        }

        @Test
        fun `no data chunk, an empty one or no WAVE takes the whole file`() {
            assertThat(keyOf(riff(fmt), AudioContainer.WAV).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(riff(fmt, chunk("data", ByteArray(0))), AudioContainer.WAV).second.payload.format).isEqualTo(KeyFormat.FILE)
            assertThat(keyOf(bytesOf("RIFF", ByteArray(4), "AVI ", fmt), AudioContainer.WAV).second.payload.format).isEqualTo(KeyFormat.FILE)
        }
    }

    @Test
    fun `any other format takes the whole file`() {
        val (_, located) = keyOf(audio, AudioContainer.OTHER)

        assertThat(located.payload.format).isEqualTo(KeyFormat.FILE)
        assertThat(located.payload.length).isEqualTo(200_000)
    }

    @Test
    fun `a walk over endless tiny structures gives up and takes the whole file`() {
        val atoms = ByteArray(8 * 1_500_000)
        for (at in atoms.indices step 8) {
            atoms[at + 3] = 8
            "free".toByteArray().copyInto(atoms, at + 4)
        }

        assertThat(keyOf(atoms, AudioContainer.MP4).second.payload.format).isEqualTo(KeyFormat.FILE)
    }

    @Nested
    inner class Sniffing {
        private fun sniff(vararg parts: Any) = PayloadLocator.sniff(ArraySource(bytesOf(*parts)))

        @Test
        fun `the container comes from the content`() {
            assertThat(sniff("OggS", ByteArray(100))).isEqualTo(AudioContainer.OGG)
            assertThat(sniff("fLaC", ByteArray(100))).isEqualTo(AudioContainer.FLAC)
            assertThat(sniff(id3v2Tag(500), "fLaC", ByteArray(100))).isEqualTo(AudioContainer.FLAC)
            assertThat(sniff(ByteArray(4), "ftyp", ByteArray(100))).isEqualTo(AudioContainer.MP4)
            assertThat(sniff("RIFF", ByteArray(4), "WAVE", ByteArray(100))).isEqualTo(AudioContainer.WAV)
        }

        @Test
        fun `anything else is left to the MPEG probe`() {
            assertThat(sniff("RIFF", ByteArray(4), "AVI ", ByteArray(100))).isEqualTo(AudioContainer.OTHER)
            assertThat(sniff(id3v2Tag(500), pattern(1_000))).isEqualTo(AudioContainer.OTHER)
            assertThat(sniff("ab")).isEqualTo(AudioContainer.OTHER)
        }
    }
}
