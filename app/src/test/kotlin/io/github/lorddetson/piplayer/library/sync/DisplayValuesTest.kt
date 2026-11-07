package io.github.lorddetson.piplayer.library.sync

import io.github.lorddetson.piplayer.library.sort.SortKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DisplayValuesTest {

    private fun row(
        title: String? = "Ёжик в тумане",
        artist: String? = "Beyoncé",
        album: String? = "Сказки",
        composer: String? = null,
        genre: String? = null,
        durationMs: Long? = 125_000,
        displayName: String = "01 Ёжик.mp3",
    ) = IndexRow(
        mediaStoreId = 1, volume = "external_primary", relativePath = "Music/", displayName = displayName, size = 1,
        modifiedSeconds = 1, generationModified = 1, mimeType = "audio/mpeg", kinds = MediaKinds(), title = title,
        artist = artist, album = album, albumArtist = null, composer = composer, genre = genre, year = null,
        trackNumber = null, durationMs = durationMs,
    )

    @Test
    fun `a tagged row shows its title, artist, album and length`() {
        val values = DisplayValues.of(row())

        assertThat(values.title).isEqualTo("Ёжик в тумане")
        assertThat(values.artist).isEqualTo("Beyoncé")
        assertThat(values.album).isEqualTo("Сказки")
        assertThat(values.durationMs).isEqualTo(125_000)
    }

    @Test
    fun `the title sorts by the collator's key of the title`() {
        assertThat(DisplayValues.of(row(title = "The Ёжик, Part II")).titleSort).isEqualTo(SortKeys.of("The Ёжик, Part II"))
    }

    @Test
    fun `the search text joins the normalized values and the file name without its extension`() {
        val values = DisplayValues.of(
            row(composer = "Шаинский", genre = "Детские", displayName = "01 Ёжик (live).mp3"),
        )

        assertThat(values.searchText).isEqualTo("ежик в тумане beyonce сказки шаинский детские 01 ежик live")
    }

    @Test
    fun `the index's unknown artist counts as none and is not searched for`() {
        val values = DisplayValues.of(row(artist = "<unknown>"))

        assertThat(values.artist).isNull()
        assertThat(values.searchText).doesNotContain("unknown")
    }

    @Test
    fun `blank values count as none`() {
        val values = DisplayValues.of(row(artist = " ", album = ""))

        assertThat(values.artist).isNull()
        assertThat(values.album).isNull()
        assertThat(values.searchText).isEqualTo("ежик в тумане 01 ежик")
    }

    @Test
    fun `a row without a title is shown under its file name without the extension`() {
        assertThat(DisplayValues.of(row(title = null, displayName = "Track.04.flac")).title).isEqualTo("Track.04")
        assertThat(DisplayValues.of(row(title = "  ", displayName = "no extension")).title).isEqualTo("no extension")
    }

    @Test
    fun `a length the index could not read counts as none`() {
        assertThat(DisplayValues.of(row(durationMs = null)).durationMs).isNull()
        assertThat(DisplayValues.of(row(durationMs = 0)).durationMs).isNull()
        assertThat(DisplayValues.of(row(durationMs = -1)).durationMs).isNull()
    }
}
