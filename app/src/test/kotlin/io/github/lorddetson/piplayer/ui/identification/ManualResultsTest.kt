package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzJson
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingSearchResponse
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.release.TrackPosition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.identification.score.TrackHint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Manual search's results (TI §3.3, §6 review screen B) on the shared MusicBrainz fixtures. */
class ManualResultsTest {

    private val hint = TrackHint("Paper Lanterns", "The Quiet Harbors", 214_000)
    private val search: RecordingSearchResponse = MusicBrainzJson.decodeFromString(FakeMusicBrainz.SEARCH.text())

    @Test
    fun `a search's recordings are scored by the similarity tier, best first, one per recording`() {
        val candidates = search.recordings.map { it.toCandidate() }
        val results = ManualResults.ofSearch(hint, candidates + candidates.first())

        assertThat(results.map { it.match.recordingId }).containsExactly(
            FakeMusicBrainz.HARBOR_RECORDING,
            "12121212-1212-4212-8212-121212121212",
        )
        assertThat(results.map { it.match.score }).isSortedAccordingTo(compareByDescending { it })
        for (result in results) {
            assertThat(result.match.score).isGreaterThan(0.0).isLessThanOrEqualTo(TextCandidateScorer.TEXT_CAP)
            assertThat(result.match.reason.source).isEqualTo(Source.TEXT_SEARCH)
            assertThat(result.match.reason.tier).isEqualTo(Tier.SIMILARITY)
            assertThat(result.release).isNull()
        }
    }

    @Test
    fun `a looked-up recording is one result`() {
        val recording: RecordingDto = MusicBrainzJson.decodeFromString(FakeMusicBrainz.HARBOR_RECORDING_LOOKUP.text())

        val results = ManualResults.ofRecording(hint, recording)

        assertThat(results).hasSize(1)
        assertThat(results.single().match.recordingId).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
        assertThat(results.single().match.score).isGreaterThan(0.5)
        assertThat(results.single().release).isNull()
    }

    @Test
    fun `a looked-up release lists its tracks in disc and track order, each with the release and its place`() {
        val release = ReviewFixtures.harborRelease

        val results = ManualResults.ofRelease(hint, release)

        assertThat(results).hasSize(11)
        assertThat(results.map { it.at }).containsExactlyElementsOf((1..11).map { TrackPosition(1, it) })
        assertThat(results.map { it.match.candidate.title }.take(3)).containsExactly("Low Tide", "North Light", "Paper Lanterns")
        assertThat(results).allSatisfy { assertThat(it.release).isSameAs(release) }
        // The song's track scores best, and its reprise, the same recording, is listed at its own place.
        assertThat(results.maxBy { it.match.score }.at).isEqualTo(TrackPosition(1, 3))
        assertThat(results[10].match.recordingId).isEqualTo(FakeMusicBrainz.HARBOR_RECORDING)
        assertThat(results[10].match.candidate.title).isEqualTo("Paper Lanterns (reprise)")
    }

    @Test
    fun `without file values the results still come, scored low`() {
        val results = ManualResults.ofSearch(TrackHint(null, null, null), search.recordings.map { it.toCandidate() })

        assertThat(results).hasSize(2)
        assertThat(results).allSatisfy { assertThat(it.match.score).isLessThan(0.5) }
    }

    @Test
    fun `a result's band is medium from 0_50, low below it, and never high`() {
        assertThat(ManualResults.band(0.95)).isEqualTo(Band.MEDIUM)
        assertThat(ManualResults.band(0.85)).isEqualTo(Band.MEDIUM)
        assertThat(ManualResults.band(0.5)).isEqualTo(Band.MEDIUM)
        assertThat(ManualResults.band(0.5 - 1e-12)).isEqualTo(Band.MEDIUM)
        assertThat(ManualResults.band(0.49)).isEqualTo(Band.NO_MATCH)
        assertThat(ManualResults.band(0.0)).isEqualTo(Band.NO_MATCH)
    }
}
