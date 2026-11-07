package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.lists.AlbumCredit
import io.github.lorddetson.piplayer.library.lists.SortKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What groups and sorts a file (docs/library.md §6.2), from the index before the tag pass and from the tags after it. */
class GroupingValuesTest {

    private val volume = "external_primary"
    private val index = IndexValues(
        title = "Index title", artist = "Index artist", album = "Index album", albumArtist = "Index album artist",
        year = 1987, trackNumber = 2005, durationMs = 1_000,
    )
    private val tags = TrackTags.from(
        mapOf(
            "TITLE" to listOf("Tag title"),
            "ARTIST" to listOf("Ёлка feat. Other"),
            "ARTISTS" to listOf("Ёлка", "Other"),
            "ALBUM" to listOf("Tag Album"),
            "ALBUMARTIST" to listOf("Tag album artist"),
            "DATE" to listOf("1999-05-01"),
            "TRACKNUMBER" to listOf("7/12"),
            "DISCNUMBER" to listOf("2/2"),
        ),
    )

    private fun unread(index: IndexValues? = this.index, path: String = "Music/Album/CD2/", name: String = "07 Song.mp3") =
        MergedValues.of(name, index, tags = null, read = false, volume = volume, relativePath = path)

    private fun read(tags: TrackTags = this.tags, path: String = "Music/Album/CD2/", name: String = "07 Song.mp3") =
        MergedValues.of(name, index, tags, read = true, volume = volume, relativePath = path)

    @Test
    fun `before the read the index groups the file`() {
        val values = unread()

        assertThat(values.albumKey).isEqualTo(
            AlbumKey.of(TrackTags(album = "Index album", albumArtist = "Index album artist"), volume, "Music/Album/CD2/"),
        )
        assertThat(values.albumArtist).isEqualTo("Index album artist")
        assertThat(values.compilation).isFalse()
        assertThat(values.year).isEqualTo(1987)
        assertThat(values.artists).containsExactly("Index artist")
    }

    @Test
    fun `the index's track number carries the disc in its thousands`() {
        assertThat(unread().discNumber).isEqualTo(2)
        assertThat(unread().trackNumber).isEqualTo(5)
        assertThat(unread(index.copy(trackNumber = 7)).let { it.discNumber to it.trackNumber }).isEqualTo(null to 7)
        assertThat(unread(index.copy(trackNumber = 1000)).let { it.discNumber to it.trackNumber }).isEqualTo(1 to null)
        assertThat(unread(index.copy(trackNumber = 0)).let { it.discNumber to it.trackNumber }).isEqualTo(null to null)
        assertThat(unread(index.copy(trackNumber = null)).let { it.discNumber to it.trackNumber }).isEqualTo(null to null)
    }

    @Test
    fun `after the read the tags group the file`() {
        val values = read()

        assertThat(values.albumKey).isEqualTo(AlbumKey.of(tags, volume, "Music/Album/CD2/"))
        assertThat(values.albumArtist).isEqualTo("Tag album artist")
        assertThat(values.year).isEqualTo(1999)
        assertThat(values.discNumber).isEqualTo(2)
        assertThat(values.trackNumber).isEqualTo(7)
        assertThat(values.artists).containsExactly("Ёлка", "Other")
    }

    @Test
    fun `the discs of one album in disc folders share its key, before and after the read`() {
        assertThat(unread(path = "Music/Album/CD1/").albumKey).isEqualTo(unread(path = "Music/Album/CD2/").albumKey)
        assertThat(read(path = "Music/Album/Disc 1/").albumKey).isEqualTo(read(path = "Music/Album/Disc 2/").albumKey)
    }

    @Test
    fun `the year comes from the date, else the original date, and only four digits make one`() {
        fun yearOf(vararg tags: Pair<String, String>) = read(TrackTags.from(tags.associate { (k, v) -> k to listOf(v) })).year

        assertThat(yearOf("DATE" to "2004")).isEqualTo(2004)
        assertThat(yearOf("ORIGINALDATE" to "1971-11-08")).isEqualTo(1971)
        assertThat(yearOf("DATE" to "2010", "ORIGINALDATE" to "1971")).isEqualTo(2010)
        assertThat(yearOf("DATE" to "'99")).isNull()
        assertThat(yearOf()).isNull()
    }

    @Test
    fun `a file without an album belongs to none, and the index's placeholder is no album`() {
        assertThat(unread(index.copy(album = null)).albumKey).isNull()
        assertThat(unread(index.copy(album = "<unknown>", albumArtist = "<unknown>")).let { it.albumKey to it.albumArtist })
            .isEqualTo(null to null)
        assertThat(read(TrackTags.from(mapOf("TITLE" to listOf("Only a title")))).albumKey).isNull()
    }

    @Test
    fun `a read file without artists keeps the displayed artist, and a file without any has none`() {
        assertThat(read(TrackTags.from(emptyMap()), name = "Name Artist - Name Title.mp3").artists).containsExactly("Name Artist")
        assertThat(unread(index.copy(artist = "<unknown>"), name = "track.mp3").artists).isEmpty()
    }

    @Test
    fun `the sort keys are the names normalized as for search`() {
        val values = read(name = "07 Ёжик.flac")

        assertThat(SortKeys.of("Ёлка, The")).isEqualTo(TextNormalizer.normalize("Ёлка, The"))
        assertThat(values.artistKey).isEqualTo(SortKeys.of("Ёлка feat. Other"))
        assertThat(values.albumTitleKey).isEqualTo(SortKeys.of("Tag Album"))
        assertThat(values.fileKey).isEqualTo(SortKeys.of("07 Ёжик"))
        assertThat(values.albumSearch.split(' ')).contains("tag", "album", "artist")
    }

    @Test
    fun `an album shows its album artist, else the one artist all its songs share, else various artists`() {
        assertThat(AlbumCredit.of(albumArtist = "Queen", compilation = true, artist = "A", oneArtist = false))
            .isEqualTo(AlbumCredit.Named("Queen"))
        assertThat(AlbumCredit.of(albumArtist = null, compilation = false, artist = "A", oneArtist = true))
            .isEqualTo(AlbumCredit.Named("A"))
        assertThat(AlbumCredit.of(albumArtist = null, compilation = false, artist = "A", oneArtist = false))
            .isEqualTo(AlbumCredit.Various)
        assertThat(AlbumCredit.of(albumArtist = null, compilation = true, artist = "A", oneArtist = true))
            .isEqualTo(AlbumCredit.Various)
        assertThat(AlbumCredit.of(albumArtist = null, compilation = false, artist = null, oneArtist = false))
            .isEqualTo(AlbumCredit.Unknown)
    }
}
