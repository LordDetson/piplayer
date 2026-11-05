package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.library.sync.DisplayValues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The precedence table of TI §7.1: user edit, confirmed identification, file tags, media index, file name. */
class MergedValuesTest {

    private val index = IndexValues(
        title = "Index title", artist = "Index artist", album = "Index album", composer = "Index composer",
        genre = "Index genre", durationMs = 125_000,
    )
    private val tags = TrackTags.from(
        mapOf(
            "TITLE" to listOf("Tag title"),
            "ARTIST" to listOf("Tag artist feat. Other"),
            "ARTISTS" to listOf("Tag artist", "Other"),
            "ALBUM" to listOf("Tag album"),
            "COMPOSER" to listOf("Tag composer"),
            "GENRE" to listOf("Tag genre", "Second genre"),
        ),
    )
    private val emptyTags = TrackTags.from(emptyMap())

    private fun merge(
        displayName: String = "Name Artist - Name Title.mp3",
        index: IndexValues? = this.index,
        tags: TrackTags? = null,
        read: Boolean = false,
        exactDurationMs: Long? = null,
        tagLengthMs: Long? = null,
        embeddedCover: Boolean = false,
        overrides: FieldOverrides = FieldOverrides.NONE,
    ) = MergedValues.of(displayName, index, tags, read, exactDurationMs, tagLengthMs, embeddedCover, overrides)

    @Test
    fun `an unread file shows the index`() {
        val values = merge()

        assertThat(values.title).isEqualTo("Index title")
        assertThat(values.artist).isEqualTo("Index artist")
        assertThat(values.album).isEqualTo("Index album")
    }

    @Test
    fun `a read file shows its tags`() {
        val values = merge(tags = tags, read = true)

        assertThat(values.title).isEqualTo("Tag title")
        assertThat(values.artist).isEqualTo("Tag artist feat. Other")
        assertThat(values.album).isEqualTo("Tag album")
    }

    @Test
    fun `once read an empty tag stays empty, the index does not fill it, only the file name`() {
        val values = merge(displayName = "Track 05.mp3", tags = emptyTags, read = true)

        assertThat(values.artist).isNull()
        assertThat(values.album).isNull()
        assertThat(values.title).isEqualTo("Track 05")
    }

    @Test
    fun `the file name fills an empty title and artist of a read file`() {
        val values = merge(tags = emptyTags, read = true)

        assertThat(values.title).isEqualTo("Name Title")
        assertThat(values.artist).isEqualTo("Name Artist")
        assertThat(values.album).isNull()
    }

    @Test
    fun `the file name fills what the index lacks in an unread file`() {
        val values = merge(index = IndexValues(title = null, artist = "<unknown>", album = null))

        assertThat(values.title).isEqualTo("Name Title")
        assertThat(values.artist).isEqualTo("Name Artist")
    }

    @Test
    fun `the index's placeholder and blanks count as none`() {
        val values = merge(
            displayName = "Track 05.mp3",
            index = IndexValues(title = " ", artist = "<unknown>", album = "", durationMs = 0),
        )

        assertThat(values.title).isEqualTo("Track 05")
        assertThat(values.artist).isNull()
        assertThat(values.album).isNull()
        assertThat(values.durationMs).isNull()
    }

    @Test
    fun `a name the parser cannot read falls back to the name without its extension`() {
        assertThat(merge(displayName = "AUD-20230101-WA0001.opus", tags = emptyTags, read = true).title)
            .isEqualTo("AUD-20230101-WA0001")
        assertThat(merge(displayName = ".mp3", tags = emptyTags, read = true).title).isEqualTo(".mp3")
    }

    @Test
    fun `tags without a read flag do not count`() {
        assertThat(merge(tags = tags, read = false).title).isEqualTo("Index title")
    }

    @Test
    fun `each overriding field wins over the tags and the others keep their level`() {
        assertThat(merge(tags = tags, read = true, overrides = FieldOverrides(title = "Edited")).let { it.title to it.artist })
            .isEqualTo("Edited" to "Tag artist feat. Other")
        assertThat(merge(tags = tags, read = true, overrides = FieldOverrides(artist = "Edited")).let { it.artist to it.album })
            .isEqualTo("Edited" to "Tag album")
        assertThat(merge(tags = tags, read = true, overrides = FieldOverrides(album = "Edited")).let { it.album to it.title })
            .isEqualTo("Edited" to "Tag title")
    }

    @Test
    fun `an override also wins over the index of an unread file`() {
        assertThat(merge(overrides = FieldOverrides(title = "Edited")).title).isEqualTo("Edited")
    }

    @Test
    fun `the exact length wins, then the index, then the tag reader's estimate`() {
        assertThat(merge(exactDurationMs = 1, tagLengthMs = 3).durationMs).isEqualTo(1)
        assertThat(merge(tagLengthMs = 3).durationMs).isEqualTo(125_000)
        assertThat(merge(index = null, tagLengthMs = 3).durationMs).isEqualTo(3)
        assertThat(merge(index = null).durationMs).isNull()
    }

    @Test
    fun `a read file keeps the index's length, unlike its other values`() {
        assertThat(merge(tags = emptyTags, read = true, tagLengthMs = 3).durationMs).isEqualTo(125_000)
    }

    @Test
    fun `the title key is the normalized title`() {
        assertThat(merge(tags = TrackTags.from(mapOf("TITLE" to listOf("The Ёжик, Part II"))), read = true).titleKey)
            .isEqualTo("the ежик part ii")
    }

    @Test
    fun `the search text holds all artists, the album, composers, genres and the file name`() {
        assertThat(merge(displayName = "01 Файл.mp3", tags = tags, read = true).searchText).isEqualTo(
            "tag title tag artist other tag album tag composer tag genre second genre 01 файл",
        )
    }

    @Test
    fun `an unread file is searched by the index's composer and genre`() {
        assertThat(merge(displayName = "01 Файл.mp3").searchText).isEqualTo(
            "index title index artist index album index composer index genre 01 файл",
        )
    }

    @Test
    fun `an overriding artist replaces the list in the search text`() {
        val text = merge(displayName = "x.mp3", tags = tags, read = true, overrides = FieldOverrides(artist = "Edited")).searchText

        assertThat(text).contains("edited").doesNotContain("other")
    }

    @Test
    fun `an unread file has no reasons yet, because the index fills albums with folder names`() {
        assertThat(merge().reasons).isNull()
    }

    @Test
    fun `a read file with everything has no reason except what is asked of it`() {
        val full = TrackTags.from(
            mapOf(
                "TITLE" to listOf("t"), "ARTIST" to listOf("a"), "ALBUM" to listOf("b"),
                "MUSICBRAINZ_TRACKID" to listOf("m"),
            ),
        )

        assertThat(merge(tags = full, read = true, embeddedCover = true).reasons).isEqualTo(0)
    }

    @Test
    fun `a read file lists each reason`() {
        val reasons = merge(displayName = "Track 05.mp3", tags = emptyTags, read = true).reasons!!

        assertThat(reasons and MergedValues.TITLE_FROM_FILE_NAME).isNotZero()
        assertThat(reasons and MergedValues.NO_ARTIST).isNotZero()
        assertThat(reasons and MergedValues.NO_ALBUM).isNotZero()
        assertThat(reasons and MergedValues.NO_COVER).isNotZero()
        assertThat(reasons and MergedValues.NO_RECORDING_ID).isNotZero()
    }

    @Test
    fun `an artist from the file name is not a missing artist`() {
        assertThat(merge(tags = emptyTags, read = true).reasons!! and MergedValues.NO_ARTIST).isZero()
    }

    @Test
    fun `an edited title does not come from the file name`() {
        val reasons = merge(tags = emptyTags, read = true, overrides = FieldOverrides(title = "Edited")).reasons!!

        assertThat(reasons and MergedValues.TITLE_FROM_FILE_NAME).isZero()
    }

    @Test
    fun `the index row form keeps the values lists had before`() {
        val values = DisplayValues.of(IndexValues(title = "Ёжик", artist = "Beyoncé", album = "Сказки", durationMs = 1), "01 Ёжик.mp3")

        assertThat(values.title).isEqualTo("Ёжик")
        assertThat(values.searchText).isEqualTo("ежик beyonce сказки 01 ежик")
    }
}
