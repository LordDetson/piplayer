package io.github.lorddetson.piplayer.identification.contract

import io.github.lorddetson.piplayer.fakes.FakeMusicBrainz
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.LookupPlanTest
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.ReleaseDetailsExtractor
import io.github.lorddetson.piplayer.identification.release.ReleaseFixtures
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Provenance and the apply request that steps 12 and 18 share (TI §7.1, §10.2; §11.1 M3 "Identification outcomes"). */
class ProvenanceTest {

    private val fetchedAt = Instant.parse("2026-11-15T10:00:00Z")
    private val details: ReleaseDetails =
        ReleaseDetailsExtractor.extract(ReleaseFixtures.lookup("release-harbor-lights.json"), FakeMusicBrainz.HARBOR_RECORDING, 3, null)!!
    private val key = "0123456789abcdef".repeat(4)

    private fun match(score: Double = 0.96, foundBy: Set<Source> = setOf(Source.FINGERPRINT, Source.TEXT_SEARCH)) =
        LookupPlanTest.match(LookupPlanTest.candidate(details.recordingId), score, foundBy)

    @Test
    fun `each provider has its license class`() {
        assertThat(LicenseClass.of(ProviderId.MUSICBRAINZ)).isEqualTo(LicenseClass.CC0)
        assertThat(LicenseClass.of(ProviderId.COVER_ART_ARCHIVE)).isEqualTo(LicenseClass.RESTRICTED)
        assertThat(LicenseClass.of(ProviderId.ACOUSTID)).isEqualTo(LicenseClass.RESTRICTED)
    }

    @Test
    fun `an identification's values come from MusicBrainz with the match's confidence, reason and IDs`() {
        val match = match()
        val provenance = Provenance.of(match, details, acoustIdTrackId = "acoustid-track", fetchedAt = fetchedAt)

        assertThat(provenance.provider).isEqualTo(ProviderId.MUSICBRAINZ)
        assertThat(provenance.licenseClass).isEqualTo(LicenseClass.CC0)
        assertThat(provenance.fetchedAt).isEqualTo(fetchedAt)
        assertThat(provenance.recordingId).isEqualTo(details.recordingId)
        assertThat(provenance.releaseTrackId).isEqualTo(details.trackId)
        assertThat(provenance.confidence).isEqualTo(0.96)
        assertThat(provenance.reason).isEqualTo(match.reason)
        assertThat(provenance.acoustIdTrackId).isEqualTo("acoustid-track")
    }

    @Test
    fun `the AcoustID track ID is kept only for a recording the fingerprint found`() {
        val byText = Provenance.of(match(foundBy = setOf(Source.TEXT_SEARCH)), details, "acoustid-track", fetchedAt)
        assertThat(byText.acoustIdTrackId).isNull()
    }

    @Test
    fun `provenance refuses details of another recording`() {
        val other = match().copy(candidate = LookupPlanTest.candidate("another-recording"))
        assertThatThrownBy { Provenance.of(other, details, null, fetchedAt) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an apply request carries the target, the ticked fields, the cover when ticked and the provenance`() {
        val provenance = Provenance.of(match(), details, null, fetchedAt)
        val cover = CoverSource(CoverEntity.RELEASE, details.releaseId)
        val request = ApplyRequest(
            target = IdentificationTarget.LibraryFile(fileId = 7, contentKey = key),
            details = details,
            fields = setOf(ProtectedField.TITLE, ProtectedField.ALBUM, ProtectedField.COVER),
            cover = cover,
            provenance = provenance,
        )
        assertThat(request.target.contentKey).isEqualTo(key)
        assertThat(request.target.startMs).isEqualTo(0)
        assertThat(request.cover).isEqualTo(cover)

        val opened = IdentificationTarget.OpenedFile(uri = "content://downloads/1", contentKey = key, displayName = "a.mp3", size = 3)
        assertThat(ApplyRequest(opened, details, setOf(ProtectedField.TITLE), null, provenance).target).isEqualTo(opened)
    }

    @Test
    fun `an apply request needs a ticked field and a cover exactly when the cover is ticked`() {
        val provenance = Provenance.of(match(), details, null, fetchedAt)
        val target = IdentificationTarget.LibraryFile(fileId = 7, contentKey = key)
        val cover = CoverSource(CoverEntity.RELEASE, details.releaseId)

        assertThatThrownBy { ApplyRequest(target, details, emptySet(), null, provenance) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ApplyRequest(target, details, setOf(ProtectedField.TITLE), cover, provenance) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ApplyRequest(target, details, setOf(ProtectedField.COVER), null, provenance) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an apply request matches its provenance to its details`() {
        val target = IdentificationTarget.LibraryFile(fileId = 7, contentKey = key)
        val foreign = Provenance.of(match(), details, null, fetchedAt).copy(recordingId = "another-recording")
        assertThatThrownBy { ApplyRequest(target, details, setOf(ProtectedField.TITLE), null, foreign) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a target's content key is the 64 hex digits the library stores`() {
        assertThatThrownBy { IdentificationTarget.LibraryFile(7, "abc") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IdentificationTarget.LibraryFile(7, key.uppercase()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IdentificationTarget.OpenedFile("content://x/1", key.dropLast(1) + "g", null, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { IdentificationTarget.LibraryFile(7, key, startMs = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
