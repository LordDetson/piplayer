package io.github.lorddetson.piplayer.nativelib.taglib

import io.github.lorddetson.piplayer.nativelib.TestFiles
import java.nio.charset.Charset
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Legacy text reaches the app with its bytes marked, the tags of one format fill in what another lacks, and the reader
 * names the codec and bit depth (TI §3.6, docs/library.md §4.2). The fixtures are made by scripts/generate_tag_fixtures.py.
 */
@RunWith(AndroidJUnit4::class)
class TagReaderLegacyTest {

    init {
        TagReader.anyProcess = true
    }

    private fun read(file: String, markLegacy: Boolean = true): TagReadResult.Success {
        val result = TestFiles.open(TestFiles.copy("tags/$file")).use { pfd ->
            TagReader.read(pfd.fd, file, markLegacy = markLegacy)
        }
        assertThat(result).isInstanceOf(TagReadResult.Success::class.java)
        return result as TagReadResult.Success
    }

    /** [text] as the reader marks it: each byte at or above 0x80 as 0xE000 plus the byte. */
    private fun marked(text: String): String = buildString {
        for (byte in text.toByteArray(Charset.forName("windows-1251"))) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (0xE000 + value).toChar())
        }
    }

    @Test
    fun id3v1TextArrivesMarkedAndTrimmedOfItsPadding() {
        val properties = read("legacy-id3v1.mp3").properties

        assertThat(properties["TITLE"]).containsExactly(marked("Кино"))
        assertThat(properties["ARTIST"]).containsExactly(marked("Цой"))
        assertThat(properties["ALBUM"]).containsExactly(marked("Чёрный альбом"))
    }

    @Test
    fun id3v2FramesOfEncodingZeroArriveMarkedAndUnicodeOnesAsTheyAre() {
        val properties = read("legacy-id3v23.mp3").properties

        assertThat(properties["TITLE"]).containsExactly(marked("Группа крови"))
        assertThat(properties["ALBUM"]).containsExactly(marked("Ёж"))
        assertThat(properties["ARTIST"]).containsExactly("Кино")
    }

    @Test
    fun riffInfoTextArrivesMarked() {
        val properties = read("legacy-riff.wav").properties

        assertThat(properties["TITLE"]).containsExactly(marked("Кино"))
        assertThat(properties["ARTIST"]).containsExactly(marked("Цой"))
    }

    @Test
    fun withoutTheFlagTextIsWhatTagLibGives() {
        val before = read("legacy-id3v1.mp3", markLegacy = false).properties["TITLE"]!!.single()

        assertThat(before).isEqualTo(String("Кино".toByteArray(Charset.forName("windows-1251")), Charsets.ISO_8859_1))
    }

    @Test
    fun aMarkedReadLeavesNoMarksForTheNextRead() {
        read("legacy-id3v1.mp3", markLegacy = true)

        val after = read("legacy-id3v1.mp3", markLegacy = false).properties["TITLE"]!!.single()

        assertThat(after.none { it.code >= 0xE000 }).isTrue()
    }

    @Test
    fun id3v1FillsOnlyWhatId3v2Lacks() {
        val properties = read("fallback-id3v1.mp3").properties

        assertThat(properties["TITLE"]).containsExactly("V2 title")
        assertThat(properties["ARTIST"]).containsExactly("V1 artist")
        assertThat(properties["ALBUM"]).containsExactly("V1 album")
    }

    @Test
    fun theReaderNamesTheCodecAndBitDepth() {
        val expected = mapOf(
            "id3v23.mp3" to ("mp3" to null),
            "adts.aac" to ("aac" to null),
            "vorbis-comment.flac" to ("flac" to 16),
            "aac.m4a" to ("aac" to null),
            "alac.m4a" to ("alac" to 16),
            "vorbis.ogg" to ("vorbis" to null),
            "opus.opus" to ("opus" to null),
            "riff-info.wav" to ("pcm" to 16),
        )
        expected.forEach { (file, technical) ->
            val result = read(file, markLegacy = false)
            assertThat(result.codec).describedAs("codec of $file").isEqualTo(technical.first)
            assertThat(result.bitDepth).describedAs("bit depth of $file").isEqualTo(technical.second)
        }
    }
}
