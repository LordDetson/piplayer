package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.musicbrainz.RecordingDto
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.musicbrainz.toCandidate
import io.github.lorddetson.piplayer.identification.release.ReleaseTracks
import io.github.lorddetson.piplayer.identification.release.TrackPosition
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.identification.score.Bands
import io.github.lorddetson.piplayer.identification.score.CandidateRelease
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.RecordingScorer
import io.github.lorddetson.piplayer.identification.score.StageFinding
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TrackHint

/**
 * One result of manual search (TI §3.3, §6 review screen B): [match], scored against the run's file values, and for a
 * track of a looked-up release that [release] with the track's place [at], which choosing it takes as its edition.
 */
data class ManualResult(val match: RecordingMatch, val release: ReleaseDto? = null, val at: TrackPosition? = null)

/** Manual search's results (TI §3.3, §6 review screen B): scored as the text search scores (TI §4.1, similarity tier). */
object ManualResults {

    /** As the bands compare scores (TI §4.1). */
    private const val SCORE_TOLERANCE = 1e-9

    /** The recordings a search found, best first, one per recording. */
    fun ofSearch(hint: TrackHint, candidates: List<TextCandidate>): List<ManualResult> =
        if (candidates.isEmpty()) emptyList() else scored(hint, candidates).map { ManualResult(it) }

    /** A looked-up recording: one result. */
    fun ofRecording(hint: TrackHint, recording: RecordingDto): List<ManualResult> = ofSearch(hint, listOf(recording.toCandidate()))

    /** A looked-up release: its tracks in disc and track order, each with the release. */
    fun ofRelease(hint: TrackHint, release: ReleaseDto): List<ManualResult> = ReleaseTracks.of(release).mapNotNull { track ->
        val recordingId = track.recordingId ?: return@mapNotNull null
        // A track without its own credit carries its recording's, or else the release's.
        val credit = track.track.artistCredit.ifEmpty { track.track.recording?.artistCredit.orEmpty() }.ifEmpty { release.artistCredit }
        val candidate = TextCandidate(
            recordingId = recordingId,
            title = track.track.title ?: track.track.recording?.title.orEmpty(),
            artistCredit = credit.joinToString("") { it.name + it.joinphrase },
            artistNames = credit.map { it.name },
            lengthMs = track.track.length ?: track.track.recording?.length,
            releases = listOf(CandidateRelease(release.title, track.at.position)),
        )
        ManualResult(scored(hint, listOf(candidate)).single(), release, track.at)
    }

    /** A result's band: medium from 0.50, otherwise no match ("Low confidence"); never high. */
    fun band(score: Double): Band = if (score >= Bands.MEDIUM - SCORE_TOLERANCE) Band.MEDIUM else Band.NO_MATCH

    private fun scored(hint: TrackHint, candidates: List<TextCandidate>): List<RecordingMatch> =
        RecordingScorer.rank(hint, listOf(StageFinding.TextSearch(candidates))).matches
}
