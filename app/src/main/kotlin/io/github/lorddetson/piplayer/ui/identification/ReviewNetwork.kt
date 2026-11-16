package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.caa.CaaImage
import io.github.lorddetson.piplayer.identification.caa.CaaReleaseIndex
import io.github.lorddetson.piplayer.identification.caa.CaaResult
import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.identification.caa.CoverUrls
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.musicbrainz.RefLookup
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.Edition
import io.github.lorddetson.piplayer.identification.release.EditionList
import io.github.lorddetson.piplayer.identification.release.EditionMapping
import io.github.lorddetson.piplayer.identification.release.EditionSource
import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.release.ReleaseDetailsExtractor
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.release.ReleaseRequest
import io.github.lorddetson.piplayer.identification.release.SuggestedEdition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.text.MusicBrainzRef
import io.github.lorddetson.piplayer.identification.text.RecordingQuery
import java.util.Locale

/**
 * The review's requests (TI §6, review screens A and B) through [services]: every one goes through the services' clients,
 * whose checks (the online gate, a provider's stop, the network) come before anything is sent. [country] is the user's
 * country for the release ranking (TI §4.2 key 5).
 */
class ReviewNetwork(
    private val services: IdentificationServices,
    private val country: () -> String? = { Locale.getDefault().country.ifEmpty { null } },
) {

    /** The front image of [details]' release at the embedding size, with the fallbacks of TI §5.2. */
    suspend fun cover(details: ReleaseDetails): CaaResult<CoverImage> {
        val sources = CoverUrls.sources(details.releaseId, details.releaseGroupId, details.coverArt)
        return if (sources.isEmpty()) CaaResult.NotFound else services.coverArtArchive.cover(sources, CoverSize.EMBED)
    }

    /** The suggested edition of another candidate [match] of [result], with the run's file values (TI §4.2, §5.1). */
    suspend fun edition(match: RecordingMatch, result: PipelineResult): SuggestedEdition? {
        val request = request(result)
        // A recording the run browsed already has its releases ranked: no second browse (TI §4.2, §5.1).
        val choice = (result.outcome as? IdentificationOutcome.Found)?.choice
        if (choice != null && match.recordingId in choice.ranked) {
            return services.releases.suggestionFor(choice, match.recordingId, request)
        }
        // Otherwise the candidate alone, as release choice ranks a winner, with the run's own file values.
        // Chosen by the user (another candidate or a manual result): its releases are ranked whatever its band.
        val ranking = Ranking(listOf(match), match.score, Band.MEDIUM)
        return services.releases.choose(ranking, request).suggested
    }

    /** The edition picker's list of [suggested]'s release group (TI §4.2 step 6). */
    suspend fun editions(suggested: SuggestedEdition, result: PipelineResult): EditionList =
        services.releases.editions(choiceOf(suggested, result), request(result))

    /** Choosing [edition] in the picker of [suggested]: the mapped edition, or null without the song (TI §4.2 step 6). */
    suspend fun openEdition(edition: Edition, suggested: SuggestedEdition, result: PipelineResult): SuggestedEdition? {
        val request = request(result)
        val track = when (val mapping = services.releases.open(edition, choiceOf(suggested, result), request)) {
            is EditionMapping.SameRecording -> mapping.track
            is EditionMapping.OtherRecording -> mapping.track
            EditionMapping.DoesNotContain -> return null
        }
        val recording = track.recordingId ?: return null
        // The tracklist was just looked up, so this comes from the release cache (TI §5.1).
        val release = if (edition.release.id == suggested.release.id) suggested.release else services.releases.release(edition.release.id) ?: return null
        // The mapped track's own place picks it among tracks of the same recording.
        val details = ReleaseDetailsExtractor.extract(release, recording, track.at.position, result.durationMs) ?: return null
        return SuggestedEdition(release, details, EditionSource.PICKED)
    }

    /** Manual search's free text (TI §3.3). */
    suspend fun search(query: RecordingQuery): List<TextCandidate> = services.musicBrainz.search(query)

    /** Manual search's pasted link or ID (TI §3.3). */
    suspend fun lookup(ref: MusicBrainzRef): RefLookup = services.musicBrainz.lookup(ref)

    /** The release's Cover Art Archive index for the cover picker (TI §5.2). */
    suspend fun releaseImages(releaseId: String): CaaResult<CaaReleaseIndex> = services.coverArtArchive.releaseIndex(releaseId)

    /** An image of a release index at [px], its original for null (TI §5.2). */
    suspend fun image(image: CaaImage, px: Int?): CaaResult<CoverImage> = services.coverArtArchive.image(image, px)

    /**
     * The release side the picker works on: the candidate's recording with [suggested] as its suggested edition and the
     * run's ranked releases, so that the recording's own releases need no mapping (TI §4.2 step 6).
     */
    private fun choiceOf(suggested: SuggestedEdition, result: PipelineResult): ReleaseChoice {
        val ranked = (result.outcome as? IdentificationOutcome.Found)?.choice?.ranked.orEmpty()
        return ReleaseChoice(suggested.details.recordingId, null, ranked, suggested, emptyList())
    }

    /** Release choice's file values of [result] (TI §4.2). */
    private fun request(result: PipelineResult): ReleaseRequest {
        val tags = result.file?.tags
        return ReleaseRequest(
            albumTag = tags?.album,
            trackTotalTag = tags?.trackTotal,
            trackNumberTag = result.hint?.trackNumber,
            durationMs = result.durationMs,
            tagReleaseId = tags?.releaseMbid,
            folder = result.file?.folder,
            country = country(),
        )
    }
}
