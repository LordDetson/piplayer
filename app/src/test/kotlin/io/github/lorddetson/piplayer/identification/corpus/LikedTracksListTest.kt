package io.github.lorddetson.piplayer.identification.corpus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LikedTracksListTest {

    // The list separates title and artist with an em dash; written as an escape to keep the source ASCII.
    private val dash = "\u2014"

    @Test
    fun `parses number, title, artist and duration`() {
        assertThat(LikedTracksList.parseLine("12. Paper Lanterns $dash The Quiet Harbors [03:45]"))
            .isEqualTo(LikedTrack(12, "Paper Lanterns", "The Quiet Harbors", 225_000))
    }

    @Test
    fun `keeps several artists as one string`() {
        assertThat(LikedTracksList.parseLine("7. Tidewater $dash North Coast, Ada Vale [02:05]")?.artist)
            .isEqualTo("North Coast, Ada Vale")
    }

    @Test
    fun `splits at the last dash, so a dash inside the title stays in the title`() {
        val track = LikedTracksList.parseLine("3. Part One $dash Part Two $dash The Band [01:02]")
        assertThat(track?.title).isEqualTo("Part One $dash Part Two")
        assertThat(track?.artist).isEqualTo("The Band")
    }

    @Test
    fun `a line without a duration has a null duration`() {
        assertThat(LikedTracksList.parseLine("5. Quiet Song $dash Someone"))
            .isEqualTo(LikedTrack(5, "Quiet Song", "Someone", null))
    }

    @Test
    fun `skips headings, blank lines and prose`() {
        val tracks = LikedTracksList.parse(
            sequenceOf(
                "# Liked tracks",
                "",
                "Collected 2 tracks from the library.",
                "1. One $dash A [01:00]",
                "2. Two $dash B [10:01]",
            ),
        )
        assertThat(tracks).extracting<Long?> { it.durationMs }.containsExactly(60_000, 601_000)
    }
}
