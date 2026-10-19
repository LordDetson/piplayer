package io.github.lorddetson.piplayer.identification.score

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TextCandidateScorerTest {

    private val tolerance = within(1e-9)

    private val hint = TrackHint(title = "Paper Lanterns", artist = "The Quiet Harbors", durationMs = 214_000)

    private fun candidate(
        title: String = "Paper Lanterns",
        credit: String = "The Quiet Harbors",
        names: List<String> = listOf(credit),
        lengthMs: Long? = 214_000,
        releases: List<CandidateRelease> = emptyList(),
    ) = TextCandidate("rec-1", title, credit, names, lengthMs, releases)

    @ParameterizedTest(name = "{0} ms -> {1}")
    @CsvSource("0, 1.0", "2000, 1.0", "-2000, 1.0", "16000, 0.5", "-16000, 0.5", "30000, 0.0", "45000, 0.0")
    fun `duration credit is 1 up to 2 s and falls linearly to 0 at 30 s`(differenceMs: Long, expected: Double) {
        assertThat(TextCandidateScorer.durationCredit(differenceMs)).isCloseTo(expected, tolerance)
    }

    @Test
    fun `a perfect text match is capped at 0,85`() {
        val score = TextCandidateScorer.score(hint, candidate())
        assertThat(score.title).isEqualTo(1.0)
        assertThat(score.artist).isEqualTo(1.0)
        assertThat(score.durationCredit).isEqualTo(1.0)
        assertThat(score.total).isCloseTo(TextCandidateScorer.TEXT_CAP, tolerance)
    }

    @Test
    fun `weights are 0,4 title, 0,3 artist, 0,2 duration and 0,1 album and track`() {
        // Duration 16 s off: credit 0.5, so 0.4 + 0.3 + 0.1 = 0.8 stays below the cap.
        val score = TextCandidateScorer.score(hint, candidate(lengthMs = 230_000))
        assertThat(score.total).isCloseTo(0.8, tolerance)
    }

    @Test
    fun `a duration difference over 30 s multiplies the score by 0,3`() {
        val score = TextCandidateScorer.score(hint, candidate(lengthMs = 254_000))
        assertThat(score.total).isCloseTo((0.4 + 0.3) * 0.3, tolerance)
    }

    @Test
    fun `a matching title with an artist below 0,5 loses 0,3`() {
        val score = TextCandidateScorer.score(hint.copy(artist = "aaaaaaaa"), candidate(credit = "bbbbbbbb"))
        assertThat(score.artist).isEqualTo(0.0)
        assertThat(score.total).isCloseTo(0.4 + 0.2 - 0.3, tolerance)
    }

    @Test
    fun `without an artist in the hint there is no artist penalty`() {
        val score = TextCandidateScorer.score(hint.copy(artist = null), candidate())
        assertThat(score.artist).isEqualTo(0.0)
        assertThat(score.total).isCloseTo(0.4 + 0.2, tolerance)
    }

    @Test
    fun `an artist without letters counts as no artist`() {
        val score = TextCandidateScorer.score(hint.copy(artist = " - "), candidate())
        assertThat(score.artist).isEqualTo(0.0)
        assertThat(score.total).isCloseTo(0.4 + 0.2, tolerance)
    }

    @Test
    fun `an artist name with an ampersand matches the first credited name whole`() {
        val score = TextCandidateScorer.score(
            hint.copy(artist = "North & Coast"),
            candidate(credit = "North & Coast feat. Ada Vale", names = listOf("North & Coast", "Ada Vale")),
        )
        assertThat(score.artist).isEqualTo(1.0)
    }

    @Test
    fun `a name that only starts with feat is not split`() {
        val score = TextCandidateScorer.score(
            hint.copy(artist = "Feather"),
            candidate(credit = "Feather feat. Ada Vale", names = listOf("Feather", "Ada Vale")),
        )
        assertThat(score.artist).isEqualTo(1.0)
    }

    @Test
    fun `an unknown recording length gives no duration credit and no duration penalty`() {
        val score = TextCandidateScorer.score(hint, candidate(lengthMs = null))
        assertThat(score.durationCredit).isEqualTo(0.0)
        assertThat(score.total).isCloseTo(0.4 + 0.3, tolerance)
    }

    @Test
    fun `an unknown file duration gives no duration credit and no duration penalty`() {
        val score = TextCandidateScorer.score(hint.copy(durationMs = null), candidate())
        assertThat(score.durationCredit).isEqualTo(0.0)
        assertThat(score.total).isCloseTo(0.4 + 0.3, tolerance)
    }

    @Test
    fun `several artists match whatever separators the two sources use`() {
        val score = TextCandidateScorer.score(
            hint.copy(artist = "North Coast, Ada Vale"),
            candidate(credit = "North Coast & Ada Vale", names = listOf("North Coast", "Ada Vale")),
        )
        assertThat(score.artist).isEqualTo(1.0)
    }

    @Test
    fun `the first credited artist alone is enough for a full artist match`() {
        val score = TextCandidateScorer.score(
            hint,
            candidate(credit = "The Quiet Harbors feat. Ada Vale", names = listOf("The Quiet Harbors", "Ada Vale")),
        )
        assertThat(score.artist).isEqualTo(1.0)
    }

    @Test
    fun `album and track agreement is 1 when both match one release`() {
        val score = TextCandidateScorer.score(
            hint.copy(album = "Harbor Lights", trackNumber = 3),
            candidate(releases = listOf(CandidateRelease("Harbor Lights", 3))),
        )
        assertThat(score.albumTrack).isEqualTo(1.0)
    }

    @Test
    fun `album and track agreement is 0,5 when only one of them matches`() {
        val albumOnly = TextCandidateScorer.score(
            hint.copy(album = "Harbor Lights", trackNumber = 3),
            candidate(releases = listOf(CandidateRelease("Harbor Lights", 7))),
        )
        val trackOnly = TextCandidateScorer.score(
            hint.copy(album = "Harbor Lights", trackNumber = 3),
            candidate(releases = listOf(CandidateRelease("Greatest Hits", 3))),
        )
        assertThat(albumOnly.albumTrack).isEqualTo(0.5)
        assertThat(trackOnly.albumTrack).isEqualTo(0.5)
    }

    @Test
    fun `album and track agreement is 0 without album and track tags`() {
        val score = TextCandidateScorer.score(hint, candidate(releases = listOf(CandidateRelease("Harbor Lights", 3))))
        assertThat(score.albumTrack).isEqualTo(0.0)
    }

    @Test
    fun `the score never drops below zero`() {
        val score = TextCandidateScorer.score(
            hint.copy(artist = "aaaaaaaa"),
            candidate(credit = "bbbbbbbb", lengthMs = 400_000),
        )
        assertThat(score.total).isGreaterThanOrEqualTo(0.0)
    }
}
