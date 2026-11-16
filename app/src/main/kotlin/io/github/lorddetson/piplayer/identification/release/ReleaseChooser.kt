package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzClient
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzLookups
import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.net.SendBlockedException
import java.io.IOException

/**
 * What release choice knows about the file besides its recording ranking (TI §4.2).
 *
 * @property tagReleaseId the release MBID from the file's tags (stage 1).
 * @property folder the file's folder as the library stores it, for the [FolderReleaseHint].
 */
data class ReleaseRequest(
    val albumTag: String? = null,
    val trackTotalTag: Int? = null,
    val trackNumberTag: Int? = null,
    val durationMs: Long? = null,
    val tagReleaseId: String? = null,
    val folder: String? = null,
    val country: String? = null,
)

/** How the suggested edition was found. */
enum class EditionSource {
    /** The release MBID in the tags, whose release contains the recording (TI §4.2 step 0). */
    TAG,

    /** The top of the release ranking (TI §4.2 step 2). */
    RANKING,
}

/**
 * The edition picker's list (TI §4.2 step 6).
 *
 * @property capped the page cap stopped the release group's browse, so the list is partial and the picker says so.
 * @property totalCount the number of releases MusicBrainz reports for the group; 0 when there is no suggested edition.
 */
data class EditionList(val editions: List<Edition>, val capped: Boolean, val totalCount: Int) {
    companion object {
        val NONE = EditionList(emptyList(), capped = false, totalCount = 0)
    }
}

/** The suggested edition: the looked-up release and the file's track on it. */
data class SuggestedEdition(val release: ReleaseDto, val details: ReleaseDetails, val source: EditionSource)

/**
 * One recording's release browse, for the identification trace (TI §10.1).
 *
 * @property capped the page cap stopped the walk: only part of the releases were ranked.
 * @property failure why the browse failed, for a recording other than the winner, which is then skipped.
 */
data class BrowseTrace(
    val recordingId: String,
    val pages: Int,
    val received: Int,
    val total: Int,
    val capped: Boolean,
    val nonOfficialFallback: Boolean,
    val failure: IOException? = null,
)

/**
 * The release side of one identification.
 *
 * @property recordingId the winning recording; null when the ranking found no match.
 * @property recording the winner as a recording lookup gave it, when it needed one ([LookupPlan.needsRecordingLookup]).
 * @property ranked each browsed recording's releases, best first.
 * @property suggested the suggested edition of the winner; null when none contains it.
 */
data class ReleaseChoice(
    val recordingId: String?,
    val recording: RecordingDto?,
    val ranked: Map<String, List<RankedRelease>>,
    val suggested: SuggestedEdition?,
    val trace: List<BrowseTrace>,
) {
    companion object {
        val NONE = ReleaseChoice(null, null, emptyMap(), null, emptyList())
    }
}

/**
 * Runs release choice for a ranked file within the caps of [LookupPlan] (TI §3 request budget, §4.2, §5.1): the
 * tagged release when it contains the winner, otherwise the browse and the ranking; the release lookup for the chosen
 * release only, through [cache]; the release group's browse only when the edition picker opens ([editions]).
 */
class ReleaseChooser(
    private val lookups: MusicBrainzLookups,
    private val cache: ReleaseCache,
    private val folderHint: FolderReleaseHint = NoFolderReleaseHint,
) {

    /**
     * The suggested edition of [ranking]'s winner and the ranked releases of every recording [LookupPlan] browses. A
     * failed browse of a recording other than the winner is skipped and traced; any other failure, and every call that
     * may not be sent, ends the choice. [onPage] hears the browse pages fetched so far for each recording.
     */
    suspend fun choose(
        ranking: Ranking,
        request: ReleaseRequest,
        onPage: (recordingId: String, pages: Int) -> Unit = { _, _ -> },
    ): ReleaseChoice {
        val best = ranking.best
        if (best == null || ranking.band == Band.NO_MATCH) return ReleaseChoice.NONE
        val winner = best.recordingId
        val recording = if (LookupPlan.needsRecordingLookup(best)) lookups.lookupRecording(winner) else null
        val context = context(request)
        val tagged = request.tagReleaseId?.let { release(it) }
            ?.let { suggestion(it, winner, request, EditionSource.TAG) }

        val ranked = LinkedHashMap<String, List<RankedRelease>>()
        val trace = mutableListOf<BrowseTrace>()
        for (id in LookupPlan.recordingsToBrowse(ranking)) {
            // A correctly tagged edition is not replaced by the ranking (TI §4.2 step 0).
            if (id == winner && tagged != null) continue
            val list = try {
                lookups.releasesOfRecording(id, LookupPlan.MAX_RELEASE_PAGES) { pages -> onPage(id, pages) }
            } catch (e: SendBlockedException) {
                throw e
            } catch (e: MusicBrainzException.UserAgentRejected) {
                throw e
            } catch (e: IOException) {
                if (id == winner) throw e
                trace += BrowseTrace(id, 0, 0, 0, capped = false, nonOfficialFallback = false, failure = e)
                continue
            }
            trace += BrowseTrace(
                id, list.pages, list.releases.size, list.totalCount, list.capped, list.nonOfficialFallback,
            )
            ranked[id] = ReleaseRanking.rank(list.releases, context)
        }
        // The release lookup only for the chosen release: the top of the winner's ranking.
        val suggested = tagged ?: ranked[winner]?.firstOrNull()?.let { top ->
            release(top.release.id)?.let { suggestion(it, winner, request, EditionSource.RANKING) }
        }
        return ReleaseChoice(winner, recording, ranked, suggested, trace)
    }

    /**
     * The suggested edition of [recordingId], a recording other than the winner that [choice] browsed (the review's
     * other candidates, TI §6): the tagged release when it contains the recording (TI §4.2 step 0), otherwise the top of
     * its ranking, with one release lookup and no second browse. Null when [choice] did not browse it.
     */
    suspend fun suggestionFor(choice: ReleaseChoice, recordingId: String, request: ReleaseRequest): SuggestedEdition? {
        val ranked = choice.ranked[recordingId] ?: return null
        request.tagReleaseId?.let { release(it) }?.let { suggestion(it, recordingId, request, EditionSource.TAG) }?.let { return it }
        return ranked.firstOrNull()?.let { top -> release(top.release.id)?.let { suggestion(it, recordingId, request, EditionSource.RANKING) } }
    }

    /** The edition picker's list (TI §4.2 step 6), browsed from the suggested release's group when it opens. */
    suspend fun editions(choice: ReleaseChoice, request: ReleaseRequest): EditionList {
        val suggested = choice.suggested ?: return EditionList.NONE
        val group = suggested.release.releaseGroup?.id ?: return EditionList(
            listOf(Edition(suggested.release, suggested = true, knownToContain = true)),
            capped = false,
            totalCount = 1,
        )
        val list = lookups.releasesOfReleaseGroup(group, LookupPlan.MAX_RELEASE_PAGES)
        val releases = list.releases
        val known = choice.ranked[choice.recordingId].orEmpty().map { it.release.id }.toSet() + suggested.release.id
        // The suggested edition is listed even when the group's browse missed it (a capped or changed list).
        val all = if (releases.any { it.id == suggested.release.id }) releases else listOf(suggested.release) + releases
        return EditionList(
            EditionPicker.editions(all, suggested.release.id, known, context(request)),
            capped = list.capped,
            totalCount = list.totalCount,
        )
    }

    /** Choosing [edition] in the picker: its tracklist (one lookup, cached) and how the file maps onto it. */
    suspend fun open(edition: Edition, choice: ReleaseChoice, request: ReleaseRequest): EditionMapping {
        val recording = choice.recordingId ?: return EditionMapping.DoesNotContain
        val suggested = choice.suggested
        val release = if (suggested != null && edition.release.id == suggested.release.id) {
            suggested.release
        } else {
            release(edition.release.id) ?: return EditionMapping.DoesNotContain
        }
        return EditionPicker.map(
            edition = release,
            identifiedRecordingId = recording,
            identifiedTitle = suggested?.details?.title ?: choice.recording?.title.orEmpty(),
            position = suggested?.details?.at,
            trackNumberTag = request.trackNumberTag,
        )
    }

    /**
     * A looked-up release, from the cache or with one lookup that then fills it. The key is the MBID in its plain form
     * (a tag may hold it in upper case or with spaces), and a release that answers with another ID (a merged one) is
     * kept under the asked ID too, so every track of the album finds it (TI §5.1).
     */
    suspend fun release(releaseId: String): ReleaseDto? {
        val key = MusicBrainzClient.plainMbid(releaseId) ?: releaseId
        return cache.get(key) ?: lookups.lookupRelease(key)?.also { cache.put(it, requestedId = key) }
    }

    private suspend fun context(request: ReleaseRequest) = ReleaseContext(
        albumTag = request.albumTag,
        trackTotalTag = request.trackTotalTag,
        trackNumberTag = request.trackNumberTag,
        durationMs = request.durationMs,
        country = request.country,
        confirmedInFolder = folderHint.confirmedReleaseIds(request.folder),
    )

    private fun suggestion(release: ReleaseDto, recording: String, request: ReleaseRequest, source: EditionSource) =
        ReleaseDetailsExtractor.extract(release, recording, request.trackNumberTag, request.durationMs)
            ?.let { SuggestedEdition(release, it, source) }
}
