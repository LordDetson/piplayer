package io.github.lorddetson.piplayer.library.values

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What makes tracks one album (TI §10.1). */
class AlbumKeyTest {

    private fun tags(album: String? = "Сказки", albumArtist: String? = "Кино", release: String? = null, compilation: Boolean = false) =
        TrackTags(album = album, albumArtist = albumArtist, releaseMbid = release, compilation = compilation)

    private fun key(tags: TrackTags, path: String = "Music/Кино/Сказки/", volume: String = "external_primary") =
        AlbumKey.of(tags, volume, path)

    @Test
    fun `no album is no key`() {
        assertThat(key(tags(album = null))).isNull()
    }

    @Test
    fun `the same title, album artist and folder are one album, in any spelling`() {
        assertThat(key(tags(album = "СКАЗКИ", albumArtist = "кино"))).isEqualTo(key(tags()))
        assertThat(key(tags(album = "Skazki!"))).isEqualTo(key(tags(album = "skazki")))
    }

    @Test
    fun `another folder is another album`() {
        assertThat(key(tags(), path = "Music/Other/")).isNotEqualTo(key(tags()))
        assertThat(key(tags(), path = "Music/Rock/")).isNotEqualTo(key(tags(), path = "Music/Rockabilly/"))
    }

    @Test
    fun `another volume is another album`() {
        assertThat(key(tags(), volume = "1234-5678")).isNotEqualTo(key(tags()))
    }

    @Test
    fun `another album artist is another album`() {
        assertThat(key(tags(albumArtist = "ДДТ"))).isNotEqualTo(key(tags()))
    }

    @Test
    fun `a compilation is apart from a plain album of the same name`() {
        assertThat(key(tags(compilation = true))).isNotEqualTo(key(tags()))
    }

    @Test
    fun `a last folder named like a disc does not split the album`() {
        val whole = key(tags(), path = "Music/Album/")

        for (disc in listOf("CD1", "cd 2", "CD-3", "Disc 2", "disc1", "Disk 1", "Диск 2", "диск1")) {
            assertThat(key(tags(), path = "Music/Album/$disc/")).describedAs(disc).isEqualTo(whole)
        }
    }

    @Test
    fun `a folder that only starts like a disc name stays`() {
        for (folder in listOf("CD Collection", "Discography", "Disco 2", "Диски")) {
            assertThat(key(tags(), path = "Music/Album/$folder/")).describedAs(folder).isNotEqualTo(key(tags(), path = "Music/Album/"))
        }
    }

    @Test
    fun `a disc folder at the root of the volume keeps its name`() {
        assertThat(key(tags(), path = "CD1/")).isNotEqualTo(key(tags(), path = "CD2/"))
    }

    @Test
    fun `a release ID alone makes the album, wherever its files are`() {
        assertThat(key(tags(release = "abc"), path = "Music/A/")).isEqualTo(key(tags(album = "Other", albumArtist = null, release = "abc"), path = "B/"))
    }
}
