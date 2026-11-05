package io.github.lorddetson.piplayer.tagging.contentkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TaggerClient
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.tagging.TaggerTesting.readFile
import io.github.lorddetson.piplayer.tagging.TaggerResult
import io.github.lorddetson.piplayer.tagging.TaggerTesting.report
import io.github.lorddetson.piplayer.tagging.outsideTarget
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The content key through the real reader in `:tagger` (TI §11.2, M2a). Expected keys come from
 * scripts/content_key_reference.py; a change here would mean re-keying every library (TI §3.1).
 */
@RunWith(AndroidJUnit4::class)
class ContentKeyReaderTest {

    private val opened = TaggerTesting.freshDatabase("content-key-test.db")
    private val database = opened.first
    private val client: TaggerClient = TaggerTesting.client(database, 30.seconds)
    private val cache = TestFiles.targetContext.cacheDir

    @After
    fun close() {
        client.close()
        database.close()
    }

    private fun bytes(asset: String) = TestFiles.assets.open(asset).use { it.readBytes() }

    private fun content(file: File): TagReport.Content =
        requireNotNull(client.readFile(file).report().content) { "No content report for ${file.name}" }

    private fun content(name: String, vararg parts: ByteArray): TagReport.Content =
        content(File(cache, name).apply { writeBytes(parts.fold(ByteArray(0)) { all, part -> all + part }) })

    private fun check(asset: String, token: String, key: String) {
        val found = content(TestFiles.copy(asset))
        assertThat(found.token).describedAs(asset).isEqualTo(token)
        assertThat(found.key).describedAs(asset).isEqualTo(key)
    }

    @Test
    fun goldenKeysOfMp3AndAdts() {
        val cases = listOf(
            Triple("playback/seek-mp3-cbr-no-header.mp3", "mp3", "15710f5275b85b3c9e9638c47c12a684d498e23742fe66bb6602b98cebe905de"),
            Triple("playback/seek-mp3-vbr-xing.mp3", "mp3", "a2b875d9178b52afbd4fd885d66967e7c2c14262c20550ee314b95fab609a05e"),
            Triple("playback/seek-mp3-vbr-xing-without-toc.mp3", "mp3", "a2b875d9178b52afbd4fd885d66967e7c2c14262c20550ee314b95fab609a05e"),
            Triple("contentkey/long.mp3", "mp3", "0f80223251d02ed46dc11e585036ec047394975bdb0eee5cf8d7a4b8eaa92cde"),
            // A real tool's copy: a new Xing frame, an ID3v2 tag and an ID3v1 tag around the same audio frames.
            Triple("contentkey/long-recopied.mp3", "mp3", "0f80223251d02ed46dc11e585036ec047394975bdb0eee5cf8d7a4b8eaa92cde"),
            Triple("playback/cover-front.mp3", "mp3", "461fca95590593c829f09741d38fa9036668316e4f5b63cd31f36241108938d1"),
            Triple("playback/cover-back-then-front.mp3", "mp3", "461fca95590593c829f09741d38fa9036668316e4f5b63cd31f36241108938d1"),
            Triple("decoding/mp3-44100-stereo.mp3", "mp3", "461fca95590593c829f09741d38fa9036668316e4f5b63cd31f36241108938d1"),
            Triple("decoding/mp3-44100-320k-stereo.mp3", "mp3", "d4cf1ea915563cc1c838e5c548131fc28c7129f66d939b32b9c6a424306911dc"),
            Triple("playback/seek-aac-adts.aac", "adts", "242c5820c5257912f53638c5ce53d7391a21dbb317567d53ca1f707cafc1ff4b"),
            Triple("playback/aac-adts-44100-stereo.aac", "adts", "1aca1d572e5bb044cca2b36c92c16cd4884e9291e862026ca9e1e71b344b7c60"),
        )
        for ((asset, token, key) in cases) check(asset, token, key)
    }

    @Test
    fun goldenKeysOfTheWalkedFormats() {
        val vorbis = "6f79a40e1cd7022253f3690627e43a4f48a6c3458ce23ca0b6c97c8e189d574d"
        val opus = "22fc3f792be4d4b4bd1d64eb4297b0527210ba277e1f8adb6ec403a9ec19cd30"
        val mp4 = "52a12c98f017d1be450f676ac23ae455ea2c04d09f6bb5353013f69b5a968b24"
        val cases = listOf(
            Triple("contentkey/long.flac", "flac", "6213a2f8f017e5e5fd65c7b41d245abf4ec1836af60933352e09b7275f29ac42"),
            Triple("playback/cover-front.flac", "flac", "0a770d9425fea6bf259169145661472962827595958e57954977c66ef70bc69d"),
            Triple("contentkey/moov-first.m4a", "mp4", mp4),
            Triple("contentkey/moov-last.m4a", "mp4", mp4),
            Triple("playback/cover-front.m4a", "mp4", "2f4497fee499dfcf7578fa347d3be4106c7cd62d72722b7b6018a2d8ad3290ff"),
            Triple("decoding/wav-44100-16bit-stereo.wav", "wav", "6f32e7112234aa9da3bc2c026242fcdb9e088409f53d247bdca98337a621e8cd"),
            Triple("contentkey/long.ogg", "ogg-vorbis", vorbis),
            Triple("contentkey/long-comments-short.ogg", "ogg-vorbis", vorbis),
            Triple("contentkey/long-comments-small-pages.ogg", "ogg-vorbis", vorbis),
            Triple("contentkey/long-comments-long.ogg", "ogg-vorbis", vorbis),
            Triple("contentkey/long-cover.ogg", "ogg-vorbis", vorbis),
            Triple("contentkey/long.opus", "opus", opus),
            Triple("contentkey/long-comments-short.opus", "opus", opus),
            Triple("contentkey/long-comments-small-pages.opus", "opus", opus),
            Triple("contentkey/long-comments-long.opus", "opus", opus),
            Triple("contentkey/long-cover.opus", "opus", opus),
            Triple("playback/cover-front.ogg", "ogg-vorbis", "69ba428a1466cb1ba0f54e1957a9fed9a260a3296ef09b80b4e44975a832f5e3"),
            Triple("playback/cover-front.opus", "opus", "85a9e1863f8c4eb81225e5b7ed62abb05336fc7b1abecaeea49d2e0060a6d29a"),
            // A chain takes the whole-file token; so do formats TagLib does not know.
            Triple("contentkey/chained.ogg", "file", "27b453503465b68a150fd0037ce3d45e58abc7f96d7f96fcd444f8d925adf9c0"),
            Triple("playback/amr-nb-8000-mono.amr", "file", "ccd76fd068bb757dc36d5f736c8340588debfa33464091a6ff64980e110c784f"),
            Triple("playback/opus-48000-stereo.webm", "file", "d6d532ade1f5f53c05975c2d5e18bcacf18bff012698f605ebf5725e6941bd42"),
        )
        for ((asset, token, key) in cases) check(asset, token, key)
    }

    private fun syncSafe(size: Int) = byteArrayOf(
        (size shr 21 and 0x7f).toByte(),
        (size shr 14 and 0x7f).toByte(),
        (size shr 7 and 0x7f).toByte(),
        (size and 0x7f).toByte(),
    )

    /** An ID3v2.4 tag with a title and [padding] bytes of padding. */
    private fun id3v2(padding: Int): ByteArray {
        val text = byteArrayOf(3) + "Paper Lanterns".toByteArray()
        val frame = "TIT2".toByteArray() + syncSafe(text.size) + byteArrayOf(0, 0) + text
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncSafe(frame.size + padding) + frame + ByteArray(padding)
    }

    private fun id3v1() = "TAG".toByteArray() + ByteArray(125) { 'x'.code.toByte() }

    private fun ape(): ByteArray {
        val item = byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0) + "Year".toByteArray() + byteArrayOf(0) + "2019".toByteArray()
        fun block(flags: Long): ByteArray {
            val size = item.size + 32
            return "APETAGEX".toByteArray() + int32(2000) + int32(size.toLong()) + int32(1) + int32(flags) + ByteArray(8)
        }
        return block(0xA0000000L) + item + block(0x80000000L)
    }

    private fun int32(value: Long) = ByteArray(4) { (value shr (8 * it)).toByte() }

    private fun flipped(bytes: ByteArray, position: Int) = bytes.copyOf().also { it[position] = (it[position] + 1).toByte() }

    @Test
    fun mp3TagsChangingLeaveTheKeyAlone() {
        val audio = bytes("contentkey/long.mp3")
        val plain = content("edit.mp3", audio).key

        val small = content("edit.mp3", id3v2(512), audio)
        val grown = content("edit.mp3", id3v2(400_000), audio)
        val v1 = content("edit.mp3", audio, id3v1())
        val apeOnly = content("edit.mp3", audio, ape())
        val all = content("edit.mp3", id3v2(2_048), audio, ape(), id3v1())

        for (edited in listOf(small, grown, v1, apeOnly, all)) {
            assertThat(edited.token).isEqualTo("mp3")
            assertThat(edited.key).isEqualTo(plain)
        }
    }

    @Test
    fun theMp3PayloadEndsAfterItsLastFrame() {
        val audio = bytes("contentkey/long.mp3")
        val plain = content("edit.mp3", audio).key

        // Bytes after the last frame that are no tag TagLib knows are outside as well.
        assertThat(content("edit.mp3", audio, ByteArray(300)).key).isEqualTo(plain)
    }

    @Test
    fun mp3AudioChangingChangesTheKey() {
        val audio = bytes("contentkey/long.mp3")
        val plain = content("edit.mp3", audio).key

        assertThat(content("edit.mp3", flipped(audio, 30_000)).key).isNotEqualTo(plain)
    }

    @Test
    fun anMp3CutOffInItsLastFrameIsStillAKeyOfMp3() {
        val audio = bytes("contentkey/long.mp3")

        val cut = content("edit.mp3", audio.copyOf(audio.size - 50))

        assertThat(cut.token).isEqualTo("mp3")
        assertThat(cut.key).isNotEqualTo(content("edit.mp3", audio).key)
    }

    @Test
    fun adtsTagsChangingLeaveTheKeyAlone() {
        val audio = bytes("playback/seek-aac-adts.aac")
        val plain = content("edit.aac", audio).key

        assertThat(content("edit.aac", id3v2(1_024), audio).key).isEqualTo(plain)
        assertThat(content("edit.aac", audio, id3v1()).key).isEqualTo(plain)
        assertThat(content("edit.aac", audio, ape()).key).isEqualTo(plain)
        assertThat(content("edit.aac", flipped(audio, 500)).key).isNotEqualTo(plain)
    }

    @Test
    fun flacMetadataChangingLeavesTheKeyAlone() {
        val file = bytes("contentkey/long.flac")
        val plain = content("edit.flac", file).key
        val afterStreamInfo = 4 + 4 + 34
        val padding = byteArrayOf(1, 0, 0x20, 0) + ByteArray(0x2000)
        val more = file.copyOfRange(0, afterStreamInfo) + padding + file.copyOfRange(afterStreamInfo, file.size)

        assertThat(content("edit.flac", more).key).isEqualTo(plain)
        assertThat(content("edit.flac", file, id3v1()).key).isEqualTo(plain)
        assertThat(content("edit.flac", flipped(file, file.size - 10)).key).isNotEqualTo(plain)
    }

    @Test
    fun mp4AudioChangingChangesTheKey() {
        val file = bytes("contentkey/moov-last.m4a")
        val plain = content("edit.m4a", file).key

        assertThat(content("edit.m4a", flipped(file, 2_000)).key).isNotEqualTo(plain)
    }

    @Test
    fun oggAudioChangingChangesTheKey() {
        val file = bytes("contentkey/long.opus")
        val plain = content("edit.opus", file).key

        assertThat(content("edit.opus", flipped(file, 5_000)).key).isNotEqualTo(plain)
    }

    /** The key of a file opened from another app, which may come without a name. */
    private fun contentWithoutName(file: File): TagReport.Content = TestFiles.open(file).use { descriptor ->
        val result = runBlocking { client.read(outsideTarget(file.path, descriptor), descriptor, null) }
        requireNotNull((result as TaggerResult.Replied).value.report.content)
    }

    @Test
    fun aFileNameNeverChangesAKey() {
        val cases = listOf(
            Triple("contentkey/long.mp3", "mp3", listOf("x.mp3", "x.bin", "x.wav", "x.flac", "x.ogg", "x.m4a", "x.ape", "noextension")),
            Triple("playback/seek-aac-adts.aac", "adts", listOf("x.aac", "x.mp3", "x.bin", "x.wav")),
            Triple("contentkey/long.flac", "flac", listOf("x.flac", "x.mp3", "x.wav", "x.bin", "x.ogg", "x.m4a")),
            Triple("contentkey/moov-last.m4a", "mp4", listOf("x.m4a", "x.mp4", "x.3gp", "x.mp3", "x.wav", "x.bin")),
            Triple("decoding/wav-44100-16bit-stereo.wav", "wav", listOf("x.wav", "x.mp3", "x.flac", "x.bin")),
            Triple("contentkey/long.ogg", "ogg-vorbis", listOf("x.ogg", "x.opus", "x.oga", "x.mp3", "x.wav", "x.bin")),
            Triple("contentkey/long.opus", "opus", listOf("x.opus", "x.ogg", "x.mp3", "x.bin")),
        )
        for ((asset, token, names) in cases) {
            val audio = bytes(asset)
            val expected = content(names.first(), audio)
            assertThat(expected.token).describedAs(asset).isEqualTo(token)
            for (name in names) {
                // A length may still have to be walked when TagLib, misled by the name, finds none.
                val named = content(name, audio)
                assertThat(named.token).describedAs("$asset as $name").isEqualTo(token)
                assertThat(named.key).describedAs("$asset as $name").isEqualTo(expected.key)
            }
            val unnamed = contentWithoutName(File(cache, names.first()).apply { writeBytes(audio) })
            assertThat(unnamed.key).describedAs("$asset without a name").isEqualTo(expected.key)
        }
    }

    @Test
    fun anMp3WhoseFirstFrameIsFarAfterItsFirstTagKeepsItsToken() {
        val audio = bytes("contentkey/long.mp3")
        val plain = content("edit.mp3", audio)

        val doubled = content("edit.mp3", id3v2(512), id3v2(5_000), audio)
        val junk = content("edit.mp3", id3v2(512), ByteArray(20_000) { 0x41 }, audio)

        assertThat(doubled.token).isEqualTo("mp3")
        assertThat(doubled.key).isEqualTo(plain.key)
        assertThat(junk.token).isEqualTo("mp3")
        assertThat(junk.key).isEqualTo(plain.key)
    }

    @Test
    fun aFlacThatTagLibRefusesKeepsItsTokenAndKey() {
        val file = bytes("contentkey/long.flac")
        val afterStreamInfo = 4 + 4 + 34
        // A zero-sized comment block: TagLib gives up on the file.
        val broken = file.copyOfRange(0, afterStreamInfo) + byteArrayOf(4, 0, 0, 0) + file.copyOfRange(afterStreamInfo, file.size)

        val report = client.readFile(File(cache, "broken.flac").apply { writeBytes(broken) }).report()

        assertThat(report.content?.token).isEqualTo("flac")
        assertThat(report.content?.key).isEqualTo(content("whole.flac", file).key)
    }

    @Test
    fun junkInsideAnMp3StaysInThePayloadBetweenItsFirstAndLastFrames() {
        val audio = bytes("contentkey/long.mp3")
        val withJunk = audio.copyOfRange(0, 70_000) + ByteArray(777) { 0x41 } + audio.copyOfRange(70_000, audio.size)

        val found = content("junk.mp3", withJunk)

        assertThat(found.token).isEqualTo("mp3")
        // From scripts/content_key_reference.py, over the same bytes.
        assertThat(found.key).isEqualTo("329a4bc31c42ce49cecdb7ecdd898ebcdf124fc4834900657ed00d283fe2e486")
    }

    @Test
    fun aFileWithNotEvenThreeFramesIsNotAnMp3() {
        // Frames of 417 bytes: the second one ends past the end of the file.
        val audio = bytes("playback/seek-mp3-cbr-no-header.mp3")

        assertThat(content("cut.mp3", audio.copyOf(700)).token).isEqualTo("file")
    }

    @Test
    fun theWalkOfOggReadsAboutWhatOtherFormatsReadAndNotItsCover() {
        val cover = ReadCounter(TestFiles.copy("contentkey/long-cover.opus"))
        val flac = ReadCounter(TestFiles.copy("contentkey/long.flac"))

        val oggRead = cover.readForKey(AudioContainer.OGG)
        val flacRead = flac.readForKey(AudioContainer.FLAC)

        assertThat(cover.file.length()).isGreaterThan(500_000)
        assertThat(flacRead).isLessThan(200_000)
        assertThat(oggRead).isLessThan(230_000)
    }

    private class ReadCounter(val file: File) {
        fun readForKey(container: AudioContainer): Long = TestFiles.open(file).use { descriptor ->
            val source = object : ByteSource {
                private val inner = FdByteSource(descriptor.fileDescriptor)
                var bytes = 0L
                override val size get() = inner.size

                override fun read(position: Long, target: ByteArray, offset: Int, length: Int) =
                    inner.read(position, target, offset, length).also { if (it > 0) bytes += it }
            }
            ContentKey.of(source, PayloadLocator.locate(source, container, null).payload)
            source.bytes
        }
    }
}
