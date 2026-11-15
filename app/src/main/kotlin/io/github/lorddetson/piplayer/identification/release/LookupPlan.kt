package io.github.lorddetson.piplayer.identification.release

import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingMatch

/**
 * The request caps of one identification (TI §3, request budget): pure decisions about which MusicBrainz requests a
 * file may cost after the recording stages.
 */
object LookupPlan {

    /** The automatic text-search variants of TI §3.3. */
    const val MAX_TEXT_VARIANTS = 4

    /** Browse pages per recording: 1 000 releases (TI §4.2 step 1). */
    const val MAX_RELEASE_PAGES = 10

    /** Recordings whose releases are browsed: the winner and at most two others within the band gap. */
    const val MAX_BROWSED_RECORDINGS = 3

    /**
     * The recordings whose releases are browsed, best first: the winner and the recordings less than the band gap
     * below it, at most [MAX_BROWSED_RECORDINGS]; none when the best score is in the no-match band.
     */
    fun recordingsToBrowse(ranking: Ranking): List<String> {
        val best = ranking.best ?: return emptyList()
        if (ranking.band == Band.NO_MATCH) return emptyList()
        // Within the gap exactly when the best would not be high for it, with the tolerance of the bands.
        return ranking.matches
            .filter { it === best || best.score - it.score < Bands.GAP - TOLERANCE }
            .take(MAX_BROWSED_RECORDINGS)
            .map { it.recordingId }
    }

    /** Whether [match] lacks a title, a credit or a length, so that it is worth one recording lookup. */
    fun needsRecordingLookup(match: RecordingMatch): Boolean = match.candidate.let {
        it.title.isBlank() || it.artistCredit.isBlank() || it.lengthMs == null
    }

    private const val TOLERANCE = 1e-9
}
