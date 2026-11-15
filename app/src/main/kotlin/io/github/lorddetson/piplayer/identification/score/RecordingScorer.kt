package io.github.lorddetson.piplayer.identification.score

import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidate
import io.github.lorddetson.piplayer.identification.acoustid.AcoustIdCandidates
import io.github.lorddetson.piplayer.identification.acoustid.toTextCandidate
import io.github.lorddetson.piplayer.identification.text.QueryOptions
import io.github.lorddetson.piplayer.identification.text.TrackHints
import kotlin.math.abs

/** What one stage of TI §3 found for the file. */
sealed interface StageFinding {

    /** Stage 1: the recording that the MusicBrainz recording ID from the file's tags names. */
    data class TagRecordingId(val recording: TextCandidate) : StageFinding

    /** Stage 2: the recordings that the file's ISRC maps to. */
    data class Isrc(val recordings: List<TextCandidate>) : StageFinding

    /** Stage 3: AcoustID's candidates for the fingerprint. */
    data class Fingerprint(val candidates: List<AcoustIdCandidate>) : StageFinding

    /** Stage 4: the recordings of the text search. */
    data class TextSearch(val candidates: List<TextCandidate>) : StageFinding
}

/** A recording with its confidence (TI §4.1). */
data class RecordingMatch(val candidate: TextCandidate, val score: Double, val reason: Reason) {
    val recordingId: String get() = candidate.recordingId
}

/**
 * Every recording found, best first, the gap between the best and the runner-up (the best score itself for a single
 * match, null without any) and the band of the best.
 */
data class Ranking(val matches: List<RecordingMatch>, val runnerUpGap: Double?, val band: Band) {
    val best: RecordingMatch? get() = matches.firstOrNull()
}

/** The combined recording confidence of TI §4.1 over every stage's findings. */
object RecordingScorer {

    const val TAG_ID_SCORE = 1.0
    const val TAG_ID_WINDOW_MS = 7_000L
    const val ISRC_SCORE = 0.95
    const val ISRC_WINDOW_MS = 2_000L

    /** A similarity below this to the file's value is a disagreement shown in the reason. */
    const val DISAGREEMENT = 0.5

    /**
     * Scores each finding by its tier, merges the recordings found by several stages (by recording ID, keeping the
     * highest score and every stage in the reason) and places the best in a band. The title is compared as the text
     * search compares it ([TrackHints.forScoring] with [options]), so text candidates score as they did in the search.
     */
    fun rank(hint: TrackHint, findings: List<StageFinding>, options: QueryOptions = QueryOptions()): Ranking {
        val file = TrackHints.forScoring(hint, options)
        val scored = findings.flatMap { finding ->
            when (finding) {
                is StageFinding.TagRecordingId ->
                    listOf(identifier(file, finding.recording, Source.TAG_RECORDING_ID, TAG_ID_SCORE, TAG_ID_WINDOW_MS))
                is StageFinding.Isrc -> {
                    val recordings = finding.recordings.distinctBy { it.recordingId }
                    // An ISRC that maps to several recordings proves none of them.
                    recordings.singleOrNull()
                        ?.let { listOf(identifier(file, it, Source.ISRC, ISRC_SCORE, ISRC_WINDOW_MS)) }
                        ?: recordings.map { similarity(file, it, Source.ISRC) }
                }
                is StageFinding.Fingerprint -> finding.candidates.mapNotNull { fingerprint(file, it) }
                is StageFinding.TextSearch -> finding.candidates.map { similarity(file, it, Source.TEXT_SEARCH) }
            }
        }
        val matches = scored.groupBy { it.recordingId }.map { (_, copies) -> merge(copies) }
            .sortedWith(
                compareByDescending<RecordingMatch> { it.score }
                    .thenBy { it.reason.source.ordinal }
                    .thenBy { it.recordingId },
            )
        val best = matches.getOrNull(0)?.score
        val runnerUp = matches.getOrNull(1)?.score
        return Ranking(matches, best?.let { Bands.gap(it, runnerUp) }, Bands.of(best, runnerUp))
    }

    /** The identifier tier when the lengths are known and within [windowMs], otherwise the similarity tier. */
    private fun identifier(
        file: TrackHint,
        candidate: TextCandidate,
        source: Source,
        tierScore: Double,
        windowMs: Long,
    ): RecordingMatch {
        val difference = difference(file, candidate)
        if (difference == null || abs(difference) > windowMs) return similarity(file, candidate, source)
        val reason = reason(file, candidate, source, Tier.IDENTIFIER)
        return RecordingMatch(candidate, penalized(tierScore, difference), reason)
    }

    /**
     * AcoustID's score times the duration credit; null for a track without a recording, which has nothing to apply. The
     * credit is taken against the hint's exact duration, like every other tier and the reason, not from
     * [AcoustIdCandidate.confidence], whose duration is whatever the caller gave [AcoustIdCandidates.from].
     */
    private fun fingerprint(file: TrackHint, found: AcoustIdCandidate): RecordingMatch? {
        val candidate = found.toTextCandidate() ?: return null
        val difference = difference(file, candidate)
        val credit = difference?.let(TextCandidateScorer::durationCredit) ?: 1.0
        val reason = reason(file, candidate, Source.FINGERPRINT, Tier.IDENTIFIER, found.acoustIdScore)
        return RecordingMatch(candidate, penalized(found.acoustIdScore * credit, difference), reason)
    }

    /** The similarity tier, whose penalties [TextCandidateScorer] applies, the title/artist one included. */
    private fun similarity(file: TrackHint, candidate: TextCandidate, source: Source): RecordingMatch {
        val text = TextCandidateScorer.score(file, candidate)
        return RecordingMatch(candidate, text.total, reason(file, candidate, source, Tier.SIMILARITY, text = text))
    }

    /**
     * The duration penalty, which applies to every tier (the title/artist penalty is the similarity tier's alone). With
     * today's windows and credit it never changes an identifier score, but the rule stays here so that tuning the
     * windows in M3 (PIM-60) cannot drop it.
     */
    private fun penalized(score: Double, differenceMs: Long?): Double =
        TextCandidateScorer.durationPenalty(score, differenceMs).coerceIn(0.0, 1.0)

    private fun difference(file: TrackHint, candidate: TextCandidate): Long? {
        val length = candidate.lengthMs ?: return null
        val duration = file.durationMs ?: return null
        return length - duration
    }

    private fun reason(
        file: TrackHint,
        candidate: TextCandidate,
        source: Source,
        tier: Tier,
        fingerprintScore: Double? = null,
        text: TextScore = TextCandidateScorer.score(file, candidate),
    ): Reason {
        // A recording AcoustID knows only by its ID has no title or credit, so it disagrees with nothing.
        val title = text.title.takeIf {
            TextCandidateScorer.present(file.title) && TextCandidateScorer.present(candidate.title)
        }
        val artist = text.artist.takeIf {
            TextCandidateScorer.hasArtist(file) && TextCandidateScorer.present(candidate.artistCredit)
        }
        val disagreement = buildSet {
            if (title != null && title < DISAGREEMENT) add(HintField.TITLE)
            if (artist != null && artist < DISAGREEMENT) add(HintField.ARTIST)
        }
        return Reason(
            source = source,
            tier = tier,
            foundBy = setOf(source),
            fingerprintScore = fingerprintScore,
            durationDeltaMs = difference(file, candidate),
            title = title,
            artist = artist,
            disagreement = disagreement,
        )
    }

    /**
     * The highest score wins and a tie goes to the stronger stage; the reason keeps every stage and the fingerprint
     * score.
     */
    private fun merge(copies: List<RecordingMatch>): RecordingMatch {
        val winner = copies.maxWith(
            compareBy<RecordingMatch> { it.score }.thenByDescending { it.reason.source.ordinal },
        )
        if (copies.size == 1) return winner
        return winner.copy(
            reason = winner.reason.copy(
                foundBy = copies.flatMapTo(mutableSetOf()) { it.reason.foundBy },
                fingerprintScore = copies.mapNotNull { it.reason.fingerprintScore }.maxOrNull(),
            ),
        )
    }
}
