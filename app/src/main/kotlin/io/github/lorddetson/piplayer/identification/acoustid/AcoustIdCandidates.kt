package io.github.lorddetson.piplayer.identification.acoustid

import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import kotlin.math.roundToLong

/**
 * A recording found through a fingerprint, or an AcoustID track without any linked recording ([recordingId] null).
 * [confidence] is the identifier tier of the recording confidence (TI §4.1).
 */
data class AcoustIdCandidate(
    val acoustIdTrackId: String,
    val acoustIdScore: Double,
    val recordingId: String?,
    val title: String?,
    /** Names joined by their join phrases, such as "A feat. B"; null when AcoustID gave no artists. */
    val artistCredit: String?,
    val lengthMs: Long?,
    /** Titles of the release groups the recording appears on, in AcoustID's order. */
    val releaseGroups: List<String>,
    val confidence: Double,
)

/** Turns an AcoustID answer into candidates. */
object AcoustIdCandidates {

    /**
     * One candidate per recording, scored as the AcoustID score times the duration credit against [fileDurationMs]
     * (when the file's or the recording's length is unknown, the score stays as it is); a recording linked to several
     * AcoustID tracks keeps its best candidate. A track without recordings becomes a candidate of its own. Best first.
     */
    fun from(response: AcoustIdResponse, fileDurationMs: Long?): List<AcoustIdCandidate> {
        val candidates = response.results.flatMap { result ->
            if (result.recordings.isEmpty()) {
                listOf(AcoustIdCandidate(result.id, result.score, null, null, null, null, emptyList(), result.score))
            } else {
                result.recordings.filter { it.id != null }.map { candidate(result, it, fileDurationMs) }
            }
        }
        val best = candidates.groupBy { it.recordingId ?: "track:${it.acoustIdTrackId}" }
            .map { (_, copies) -> copies.maxWith(compareBy({ it.confidence }, { it.acoustIdScore })) }
        return best.sortedWith(
            compareByDescending<AcoustIdCandidate> { it.confidence }.thenByDescending { it.acoustIdScore },
        )
    }

    private fun candidate(
        result: AcoustIdResultDto,
        recording: AcoustIdRecordingDto,
        fileDurationMs: Long?,
    ): AcoustIdCandidate {
        val lengthMs = recording.duration?.let { (it * 1_000).roundToLong() }
        val credit = if (fileDurationMs != null && lengthMs != null) {
            TextCandidateScorer.durationCredit(fileDurationMs - lengthMs)
        } else {
            1.0
        }
        return AcoustIdCandidate(
            acoustIdTrackId = result.id,
            acoustIdScore = result.score,
            recordingId = recording.id,
            title = recording.title?.takeIf { it.isNotEmpty() },
            artistCredit = recording.artists.takeIf { it.isNotEmpty() }?.joinToString("") { it.name + it.joinPhrase },
            lengthMs = lengthMs,
            releaseGroups = recording.releaseGroups.mapNotNull { it.title },
            confidence = result.score * credit,
        )
    }
}
