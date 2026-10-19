package io.github.lorddetson.piplayer.identification.musicbrainz

import io.github.lorddetson.piplayer.identification.score.CandidateRelease
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecordingSearchDtosTest {

    private val response = MusicBrainzJson.decodeFromString<RecordingSearchResponse>(fixture("recording-search.json"))

    @Test
    fun `reads the search response and skips fields it does not know`() {
        assertThat(response.count).isEqualTo(2)
        assertThat(response.recordings).hasSize(2)
        val first = response.recordings[0]
        assertThat(first.score).isEqualTo(100)
        assertThat(first.length).isEqualTo(214_000)
        assertThat(first.video).isNull()
        assertThat(first.isrcs).containsExactly("XXA001900001")
        assertThat(first.releases[2].releaseGroup?.secondaryTypes).containsExactly("Live")
    }

    @Test
    fun `fields the server leaves out get their defaults`() {
        val second = response.recordings[1]
        assertThat(second.length).isNull()
        assertThat(second.video).isTrue()
        assertThat(second.releases).isEmpty()
        assertThat(second.artistCredit.single().joinphrase).isEmpty()
    }

    @Test
    fun `a score sent as a string, as in the documentation's example, is read too`() {
        val json = """{"recordings":[{"id":"x","score":"100","title":"T"}]}"""
        assertThat(MusicBrainzJson.decodeFromString<RecordingSearchResponse>(json).recordings.single().score)
            .isEqualTo(100)
    }

    @Test
    fun `the credit string joins names with their join phrases`() {
        assertThat(response.recordings[0].creditString()).isEqualTo("The Quiet Harbors feat. Ada Vale")
        assertThat(response.recordings[1].creditString()).isEqualTo("The Quiet Harbors")
    }

    @Test
    fun `a candidate lists every track of each release that carries the recording`() {
        val candidate = response.recordings[0].toCandidate()
        assertThat(candidate.recordingId).isEqualTo("11111111-1111-4111-8111-111111111111")
        assertThat(candidate.title).isEqualTo("Paper Lanterns")
        assertThat(candidate.artistCredit).isEqualTo("The Quiet Harbors feat. Ada Vale")
        assertThat(candidate.artistNames).containsExactly("The Quiet Harbors", "Ada Vale")
        assertThat(candidate.lengthMs).isEqualTo(214_000)
        // The release appears once per linked track (3 and 11); a non-numeric "B1" falls back to the position.
        assertThat(candidate.releases).containsExactly(
            CandidateRelease("Harbor Lights", 3),
            CandidateRelease("Harbor Lights", 11),
            CandidateRelease("Live at the Pier", 4),
        )
    }

    @Test
    fun `a duplicate entry for the same track is listed once`() {
        val json = """{"recordings":[{"id":"x","title":"T","releases":[
            {"id":"r","title":"R","media":[{"track-offset":0,"track":[{"id":"t","number":"1"}]}]},
            {"id":"r","title":"R","media":[{"track-offset":0,"track":[{"id":"t","number":"1"}]}]}]}]}"""
        val candidate = MusicBrainzJson.decodeFromString<RecordingSearchResponse>(json).recordings.single().toCandidate()
        assertThat(candidate.releases).containsExactly(CandidateRelease("R", 1))
    }

    @Test
    fun `an explicit null for a field with a default is read as the default`() {
        val json = """{"recordings":[{"id":"x","title":"T","artist-credit":[{"name":"A","joinphrase":null}],
            "releases":null,"isrcs":null}]}"""
        val recording = MusicBrainzJson.decodeFromString<RecordingSearchResponse>(json).recordings.single()
        assertThat(recording.artistCredit.single().joinphrase).isEmpty()
        assertThat(recording.releases).isEmpty()
        assertThat(recording.isrcs).isEmpty()
    }

    @Test
    fun `a candidate without a length keeps it unknown`() {
        assertThat(response.recordings[1].toCandidate().lengthMs).isNull()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/musicbrainz/$name")) { "Missing fixture $name" }.readText()
}
