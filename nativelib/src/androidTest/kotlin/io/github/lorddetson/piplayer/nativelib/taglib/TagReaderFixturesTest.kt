package io.github.lorddetson.piplayer.nativelib.taglib

import io.github.lorddetson.piplayer.nativelib.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * TagLib reads the tag fixtures of every format (TI §11.2, M0). The fixtures are one-second tones made by
 * scripts/generate_tag_fixtures.py; the expected values below are the ones that script writes.
 */
@RunWith(Parameterized::class)
class TagReaderFixturesTest(private val file: String, private val fixture: Fixture) {

    class Fixture(val tags: Map<String, String>, val sampleRate: Int, val cover: Boolean)

    companion object {
        private val latin = mapOf(
            "TITLE" to "Paper Lanterns",
            "ARTIST" to "The Quiet Harbors",
            "ALBUM" to "Harbor Lights",
            "DATE" to "2019",
            "GENRE" to "Ambient",
        )
        private val cyrillic = mapOf(
            "TITLE" to "Бумажные фонари",
            "ARTIST" to "Тихая Гавань",
            "ALBUM" to "Огни гавани",
            "DATE" to "2019",
            "GENRE" to "Эмбиент",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> = listOf(
            "id3v23.mp3" to Fixture(latin, 44_100, cover = true),
            "id3v24.mp3" to Fixture(latin, 44_100, cover = true),
            "id3v1.mp3" to Fixture(latin, 44_100, cover = false),
            "vorbis-comment.flac" to Fixture(latin, 44_100, cover = true),
            "aac.m4a" to Fixture(latin, 44_100, cover = true),
            "alac.m4a" to Fixture(latin, 44_100, cover = false),
            "vorbis.ogg" to Fixture(latin, 44_100, cover = false),
            "opus.opus" to Fixture(latin, 48_000, cover = false),
            "riff-info.wav" to Fixture(latin, 44_100, cover = false),
            "cyrillic.flac" to Fixture(cyrillic, 44_100, cover = false),
            "cyrillic-id3v23.mp3" to Fixture(cyrillic, 44_100, cover = false),
        ).map { (file, fixture) -> arrayOf(file, fixture) }
    }

    private fun read(fileName: String? = file): TagReadResult.Success {
        val result = TestFiles.open(TestFiles.copy("tags/$file")).use { pfd ->
            TagReader.read(pfd.fd, fileName, readPictures = true)
        }
        assertThat(result).isInstanceOf(TagReadResult.Success::class.java)
        return result as TagReadResult.Success
    }

    @Test
    fun readsTheTags() {
        val properties = read().properties
        fixture.tags.forEach { (key, value) -> assertThat(properties[key]).describedAs(key).containsExactly(value) }
        assertThat(properties["TRACKNUMBER"]?.single()).describedAs("TRACKNUMBER").startsWith("3")
    }

    @Test
    fun detectsTheFormatFromTheContentWithoutAFileName() {
        assertThat(read(fileName = null).properties["TITLE"]).containsExactly(fixture.tags.getValue("TITLE"))
    }

    @Test
    fun readsTheAudioProperties() {
        val audio = read().audio!!
        assertThat(audio.sampleRate).isEqualTo(fixture.sampleRate)
        assertThat(audio.channels).isEqualTo(2)
        assertThat(audio.lengthMs).isBetween(950, 1_100)
    }

    @Test
    fun readsTheEmbeddedCover() {
        val pictures = read().pictures
        if (!fixture.cover) {
            assertThat(pictures).isEmpty()
            return
        }
        val cover = pictures.single()
        assertThat(cover.mimeType).isEqualTo("image/png")
        // A PNG file starts with these eight bytes.
        assertThat(cover.data.copyOf(8)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(),
            'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
