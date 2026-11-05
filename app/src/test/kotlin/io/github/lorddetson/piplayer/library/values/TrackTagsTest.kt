package io.github.lorddetson.piplayer.library.values

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrackTagsTest {

    private fun tags(vararg values: Pair<String, List<String>>) = TrackTags.from(mapOf(*values))
    private fun tag(key: String, vararg values: String) = tags(key to values.toList())

    @Test
    fun `single values are taken trimmed, the first that is present`() {
        val tags = tags("TITLE" to listOf(" ", " Ёжик "), "ALBUM" to listOf("Сказки", "Другой"))

        assertThat(tags.title).isEqualTo("Ёжик")
        assertThat(tags.album).isEqualTo("Сказки")
    }

    @Test
    fun `blank values and the placeholder count as none`() {
        val tags = tags("TITLE" to listOf(" "), "ARTIST" to listOf("<unknown>"), "ALBUM" to emptyList())

        assertThat(tags.title).isNull()
        assertThat(tags.artist).isNull()
        assertThat(tags.album).isNull()
    }

    @Test
    fun `the credited artist is ARTIST and the list is ARTISTS`() {
        val tags = tags("ARTIST" to listOf("Kino feat. DDT"), "ARTISTS" to listOf("Kino", "DDT"))

        assertThat(tags.artist).isEqualTo("Kino feat. DDT")
        assertThat(tags.artists).containsExactly("Kino", "DDT")
    }

    @Test
    fun `several ARTIST values without a list are the list and show joined`() {
        val tags = tag("ARTIST", "Kino", "DDT")

        assertThat(tags.artist).isEqualTo("Kino; DDT")
        assertThat(tags.artists).containsExactly("Kino", "DDT")
    }

    @Test
    fun `a list alone also gives the credited name`() {
        assertThat(tag("ARTISTS", "Kino", "DDT").artist).isEqualTo("Kino; DDT")
    }

    @Test
    fun `a credited name with a slash or a semicolon is not split into a list`() {
        assertThat(tag("ARTIST", "AC/DC; Friends").artists).containsExactly("AC/DC; Friends")
    }

    @Test
    fun `list fields written joined by a semicolon are split`() {
        val tags = tags(
            "GENRE" to listOf("Rock; Pop", "Jazz"),
            "ARTISTS" to listOf("Kino; DDT"),
            "COMPOSER" to listOf("Цой;Шевчук"),
            "ALBUMARTISTS" to listOf("Kino; DDT"),
        )

        assertThat(tags.genres).containsExactly("Rock", "Pop", "Jazz")
        assertThat(tags.artists).containsExactly("Kino", "DDT")
        assertThat(tags.composers).containsExactly("Цой", "Шевчук")
        assertThat(tags.albumArtists).containsExactly("Kino", "DDT")
    }

    @Test
    fun `the album artist comes from either key`() {
        assertThat(tag("ALBUMARTIST", "Kino").albumArtist).isEqualTo("Kino")
        assertThat(tag("ALBUMARTIST", "Kino").albumArtists).containsExactly("Kino")
        assertThat(tag("ALBUMARTISTS", "Kino", "DDT").albumArtist).isEqualTo("Kino; DDT")
    }

    @Test
    fun `track and disc positions split a total written with a slash`() {
        val tags = tags("TRACKNUMBER" to listOf("3/12"), "DISCNUMBER" to listOf("1/2"))

        assertThat(tags.trackNumber).isEqualTo(3)
        assertThat(tags.trackTotal).isEqualTo(12)
        assertThat(tags.discNumber).isEqualTo(1)
        assertThat(tags.discTotal).isEqualTo(2)
    }

    @Test
    fun `a total of its own wins over the one after the slash`() {
        val tags = tags("TRACKNUMBER" to listOf("03/12"), "TRACKTOTAL" to listOf("14"), "DISCTOTAL" to listOf("2"))

        assertThat(tags.trackNumber).isEqualTo(3)
        assertThat(tags.trackTotal).isEqualTo(14)
        assertThat(tags.discTotal).isEqualTo(2)
    }

    @Test
    fun `a position that is no number or zero counts as none`() {
        assertThat(tag("TRACKNUMBER", "A1").trackNumber).isNull()
        assertThat(tag("TRACKNUMBER", "0").trackNumber).isNull()
        assertThat(tag("TRACKNUMBER", "0/0").trackTotal).isNull()
        assertThat(tag("TRACKNUMBER", "99999999999").trackNumber).isNull()
    }

    @Test
    fun `partial dates stay partial`() {
        assertThat(tag("DATE", "1997").date).isEqualTo("1997")
        assertThat(tag("DATE", "1997-05").date).isEqualTo("1997-05")
        assertThat(tag("ORIGINALDATE", "1997-05-12").originalDate).isEqualTo("1997-05-12")
    }

    @Test
    fun `a compilation is marked by a true value`() {
        assertThat(tag("COMPILATION", "1").compilation).isTrue()
        assertThat(tag("COMPILATION", "true").compilation).isTrue()
        assertThat(tag("COMPILATION", "0").compilation).isFalse()
        assertThat(tags().compilation).isFalse()
    }

    @Test
    fun `replay gain values are read as numbers`() {
        val tags = tags(
            "REPLAYGAIN_TRACK_GAIN" to listOf("-6.50 dB"),
            "REPLAYGAIN_TRACK_PEAK" to listOf("0.988"),
            "REPLAYGAIN_ALBUM_GAIN" to listOf("+1,25 dB"),
            "REPLAYGAIN_ALBUM_PEAK" to listOf("loud"),
        )

        assertThat(tags.trackGainDb).isEqualTo(-6.5)
        assertThat(tags.trackPeak).isEqualTo(0.988)
        assertThat(tags.albumGainDb).isEqualTo(1.25)
        assertThat(tags.albumPeak).isNull()
    }

    @Test
    fun `the other typed fields come from their keys`() {
        val tags = tags(
            "TITLESORT" to listOf("Ёжик, The"),
            "ARTISTSORT" to listOf("Kino"),
            "ALBUMSORT" to listOf("Сказки"),
            "ALBUMARTISTSORT" to listOf("Kino"),
            "DISCSUBTITLE" to listOf("Live"),
            "LABEL" to listOf("Мелодия"),
            "CATALOGNUMBER" to listOf("C60"),
            "BARCODE" to listOf("123"),
            "ISRC" to listOf("RU123"),
            "MUSICBRAINZ_TRACKID" to listOf("recording"),
            "MUSICBRAINZ_ALBUMID" to listOf("release"),
            "MUSICBRAINZ_RELEASEGROUPID" to listOf("group"),
            "MUSICBRAINZ_RELEASETRACKID" to listOf("track"),
            "MUSICBRAINZ_ARTISTID" to listOf("a", "b"),
            "MUSICBRAINZ_ALBUMARTISTID" to listOf("c"),
        )

        assertThat(tags.titleSort).isEqualTo("Ёжик, The")
        assertThat(tags.artistSort).isEqualTo("Kino")
        assertThat(tags.albumSort).isEqualTo("Сказки")
        assertThat(tags.albumArtistSort).isEqualTo("Kino")
        assertThat(tags.discSubtitle).isEqualTo("Live")
        assertThat(tags.label).isEqualTo("Мелодия")
        assertThat(tags.catalogNumber).isEqualTo("C60")
        assertThat(tags.barcode).isEqualTo("123")
        assertThat(tags.isrc).isEqualTo("RU123")
        assertThat(tags.recordingMbid).isEqualTo("recording")
        assertThat(tags.releaseMbid).isEqualTo("release")
        assertThat(tags.releaseGroupMbid).isEqualTo("group")
        assertThat(tags.releaseTrackMbid).isEqualTo("track")
        assertThat(tags.artistMbids).containsExactly("a", "b")
        assertThat(tags.albumArtistMbids).containsExactly("c")
    }

    @Test
    fun `no tags at all give an empty value`() {
        val tags = tags()

        assertThat(tags.title).isNull()
        assertThat(tags.artists).isEmpty()
        assertThat(tags.genres).isEmpty()
        assertThat(tags.trackNumber).isNull()
    }
}
