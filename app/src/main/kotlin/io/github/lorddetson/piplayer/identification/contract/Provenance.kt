package io.github.lorddetson.piplayer.identification.contract

import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import java.time.Instant

/** The license class of a decision's values (TI §7.1, §10.2). */
enum class LicenseClass {
    /** Public domain, as MusicBrainz core data. */
    CC0,

    /** Under terms that limit reuse: Cover Art Archive images stay the rights holders', AcoustID data is share-alike. */
    RESTRICTED,

    /** Not known, as for a value from a source without stated terms. */
    UNKNOWN,
    ;

    companion object {
        /** The class of what [provider] gives (TI §9). */
        fun of(provider: ProviderId): LicenseClass = when (provider) {
            ProviderId.MUSICBRAINZ -> CC0
            ProviderId.COVER_ART_ARCHIVE -> RESTRICTED
            ProviderId.ACOUSTID -> RESTRICTED
        }
    }
}

/**
 * Where a decision's values come from (TI §7.1), stored once per decision in the user database (TI §10.2, M3 step 12):
 * the [provider] of the values with their [licenseClass], when they were fetched, the recording and release track IDs,
 * the AcoustID track ID of a recording the fingerprint found (TI §3.2), and the match's [confidence] and [reason]
 * (TI §4.1).
 */
data class Provenance(
    val provider: ProviderId,
    val fetchedAt: Instant,
    val licenseClass: LicenseClass,
    val recordingId: String,
    val releaseTrackId: String?,
    val acoustIdTrackId: String?,
    val confidence: Double,
    val reason: Reason,
) {
    companion object {

        /**
         * The provenance of an identification: MusicBrainz values for [match], with [details] of the chosen release
         * (null when no release was chosen). [acoustIdTrackId] is kept only when the fingerprint found the recording.
         */
        fun of(match: RecordingMatch, details: ReleaseDetails?, acoustIdTrackId: String?, fetchedAt: Instant): Provenance {
            require(details == null || details.recordingId == match.recordingId) {
                "The release details belong to another recording"
            }
            return Provenance(
                provider = ProviderId.MUSICBRAINZ,
                fetchedAt = fetchedAt,
                licenseClass = LicenseClass.of(ProviderId.MUSICBRAINZ),
                recordingId = match.recordingId,
                releaseTrackId = details?.trackId,
                acoustIdTrackId = acoustIdTrackId?.takeIf { Source.FINGERPRINT in match.reason.foundBy },
                confidence = match.score,
                reason = match.reason,
            )
        }
    }
}
