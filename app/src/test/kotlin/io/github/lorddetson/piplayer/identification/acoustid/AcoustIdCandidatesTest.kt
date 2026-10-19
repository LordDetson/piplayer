package io.github.lorddetson.piplayer.identification.acoustid

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class AcoustIdCandidatesTest {

    private val json = requireNotNull(javaClass.getResource("/acoustid/lookup.json")).readText()
    private val fixture = AcoustIdJson.decodeFromString<AcoustIdResponse>(json)

    private fun recording(id: String?, duration: Double?, title: String? = "Song") =
        AcoustIdRecordingDto(id = id, title = title, duration = duration)

    private fun response(vararg results: AcoustIdResultDto) = AcoustIdResponse("ok", results.toList())

    @Test
    fun `the fixture becomes one candidate per recording and one for the track without recordings`() {
        val candidates = AcoustIdCandidates.from(fixture, fileDurationMs = 214_000)

        assertThat(candidates.map { it.recordingId }).containsExactly(
            "b0e4d1f2-1111-4111-8111-000000000001",
            "b0e4d1f2-1111-4111-8111-000000000003",
            null,
            "b0e4d1f2-1111-4111-8111-000000000002",
        )
        val best = candidates[0]
        assertThat(best.acoustIdTrackId).isEqualTo("6f1c1f4e-3b0d-4c8e-9a52-1d2e3f405162")
        assertThat(best.title).isEqualTo("Paper Lanterns")
        assertThat(best.artistCredit).isEqualTo("The Quiet Harbors feat. Mira Vale")
        assertThat(best.lengthMs).isEqualTo(214_400)
        assertThat(best.releaseGroups).containsExactly("Harbor Lights", "Quiet Songs")
        assertThat(best.confidence).isEqualTo(0.97)
    }

    @Test
    fun `a recording with only its ID keeps the AcoustID score`() {
        val idOnly = AcoustIdCandidates.from(fixture, 214_000).single { it.recordingId?.endsWith("3") == true }
        assertThat(idOnly.title).isNull()
        assertThat(idOnly.artistCredit).isNull()
        assertThat(idOnly.lengthMs).isNull()
        assertThat(idOnly.confidence).isEqualTo(0.97)
    }

    @Test
    fun `a track without recordings is kept with its own score`() {
        val bare = AcoustIdCandidates.from(fixture, 214_000).single { it.recordingId == null }
        assertThat(bare.acoustIdTrackId).isEqualTo("7a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9")
        assertThat(bare.confidence).isEqualTo(0.41)
        assertThat(bare.releaseGroups).isEmpty()
    }

    @Test
    fun `the duration credit scales the score`() {
        val result = AcoustIdResultDto(
            "t1", 0.8,
            listOf(recording("close", 214.4), recording("16s-off", 230.0), recording("37s-off", 251.0)),
        )
        val byId = AcoustIdCandidates.from(response(result), fileDurationMs = 214_000).associateBy { it.recordingId }

        assertThat(byId.getValue("close").confidence).isEqualTo(0.8, within(1e-9))
        // 16 s off: the credit falls linearly from 1 at 2 s to 0 at 30 s, so it is 0.5.
        assertThat(byId.getValue("16s-off").confidence).isEqualTo(0.4, within(1e-9))
        assertThat(byId.getValue("37s-off").confidence).isZero()
    }

    @Test
    fun `without the file's length the score stays as it is`() {
        val result = AcoustIdResultDto("t1", 0.8, listOf(recording("r1", 251.0)))
        assertThat(AcoustIdCandidates.from(response(result), fileDurationMs = null).single().confidence).isEqualTo(0.8)
    }

    @Test
    fun `a recording linked to several tracks keeps its best candidate`() {
        val weak = AcoustIdResultDto("weak-track", 0.55, listOf(recording("r1", 200.0)))
        val strong = AcoustIdResultDto("strong-track", 0.9, listOf(recording("r1", 200.0)))
        val candidates = AcoustIdCandidates.from(response(weak, strong), fileDurationMs = 200_000)

        val only = candidates.single()
        assertThat(only.acoustIdTrackId).isEqualTo("strong-track")
        assertThat(only.confidence).isEqualTo(0.9)
    }

    @Test
    fun `recordings without an ID are skipped`() {
        val result = AcoustIdResultDto("t1", 0.8, listOf(recording(null, 200.0), recording("r1", 200.0)))
        assertThat(AcoustIdCandidates.from(response(result), 200_000).map { it.recordingId }).containsExactly("r1")
    }

    @Test
    fun `ties in confidence go to the higher AcoustID score`() {
        val a = AcoustIdResultDto("a", 0.6, listOf(recording("far", 216.0)))
        val b = AcoustIdResultDto("b", 0.3, listOf(recording("near", 200.0)))
        // "far" is 16 s off (credit 0.5): 0.6 x 0.5 = 0.3, the same as "near" at 0.3 x 1.
        val candidates = AcoustIdCandidates.from(response(b, a), fileDurationMs = 200_000)
        assertThat(candidates.map { it.recordingId }).containsExactly("far", "near")
    }

    @Test
    fun `an empty answer gives no candidates`() {
        assertThat(AcoustIdCandidates.from(AcoustIdResponse("ok"), 200_000)).isEmpty()
    }
}
