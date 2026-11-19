package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecidedField.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagProjectionTest {
    @Test
    fun `vorbis keeps discrete artist names and separate position totals`() {
        val result = projectTags(
            TagFileFormat.VORBIS,
            emptyMap(),
            mapOf(
                ARTIST to "A feat. B",
                ARTIST_CREDITS to DecidedField.credits(listOf("A" to " feat. ", "B" to "")),
                TRACK_NUMBER to "3", TRACK_TOTAL to "12",
                DISC_NUMBER to "2", DISC_TOTAL to "4",
                ORIGINAL_DATE to "1997-05",
            ),
        )
        assertThat(result.properties).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "ARTIST" to listOf("A feat. B"), "ARTISTS" to listOf("A", "B"),
                "TRACKNUMBER" to listOf("3"), "TRACKTOTAL" to listOf("12"),
                "DISCNUMBER" to listOf("2"), "DISCTOTAL" to listOf("4"),
                "ORIGINALDATE" to listOf("1997-05"),
            ),
        )
    }

    @Test
    fun `id3v23 joins names and keeps unsupported disc subtitle in PiPlayer`() {
        val result = projectTags(
            TagFileFormat.ID3V23,
            mapOf("TRACKNUMBER" to listOf("4/12")),
            mapOf(
                TRACK_NUMBER to "7", DISC_SUBTITLE to "Bonus", ORIGINAL_DATE to "1997-05",
                ARTIST_CREDITS to DecidedField.credits(listOf("A" to " & ", "B" to "")),
            ),
        )
        assertThat(result.properties).isEqualTo(
            mapOf("TRACKNUMBER" to listOf("7/12"), "ORIGINALDATE" to listOf("1997"), "ARTISTS" to listOf("A; B")),
        )
        assertThat(result.unsupported).containsExactly(DISC_SUBTITLE)
    }

    @Test
    fun `identification projects no protected raw tag keys`() {
        val current = mapOf(
            "TITLE" to listOf("Old"), "LYRICS" to listOf("My lyrics"),
            "REPLAYGAIN_TRACK_GAIN" to listOf("-3.0 dB"), "COMMENT:ITUNSMPB" to listOf("gapless"),
        )
        val projected = projectTags(TagFileFormat.MP4, current, mapOf(TITLE to "New", COVER to "5"))

        val diff = TagDiff.between(current, projected.properties)
        assertThat(diff.changes.keys).containsExactly("TITLE")
        assertThat(diff.properties).isEqualTo(current + ("TITLE" to listOf("New")))
    }
}
