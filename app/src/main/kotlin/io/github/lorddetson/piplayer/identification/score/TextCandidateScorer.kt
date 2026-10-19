package io.github.lorddetson.piplayer.identification.score

import io.github.lorddetson.piplayer.identification.text.Similarity
import io.github.lorddetson.piplayer.identification.text.Spaces
import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What is known about a file before a text search: from tags or from the parsed file name. */
data class TrackHint(
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
    val album: String? = null,
    val trackNumber: Int? = null,
)

/** A recording returned by a text search, reduced to what scoring needs. */
data class TextCandidate(
    val recordingId: String,
    val title: String,
    /** The credit as printed, names joined by their join phrases, for example "A feat. B". */
    val artistCredit: String,
    val artistNames: List<String>,
    val lengthMs: Long?,
    val releases: List<CandidateRelease> = emptyList(),
)

data class CandidateRelease(val title: String, val trackNumber: Int?)

data class TextScore(
    val title: Double,
    val artist: Double,
    val durationCredit: Double,
    val albumTrack: Double,
    val total: Double,
)

/** Similarity tier of the recording confidence (TI §4.1) for candidates found by text search. */
object TextCandidateScorer {

    const val TEXT_CAP = 0.85

    private const val FULL_CREDIT_MS = 2_000L
    private const val NO_CREDIT_MS = 30_000L
    private const val DURATION_PENALTY_FACTOR = 0.3
    private const val ARTIST_PENALTY = 0.3
    private const val MATCH = 0.9

    /** Separators between artists; "feat" only as a word of its own, so "Feather" stays whole. */
    private val artistSeparators =
        Regex("""\s*(?:,|&|;|\bfeaturing\b|\bfeat\b\.?|\bft\b\.?)\s*""", RegexOption.IGNORE_CASE)

    fun score(hint: TrackHint, candidate: TextCandidate): TextScore {
        val title = hint.title?.let { Similarity.title(it, candidate.title) } ?: 0.0
        // An artist without letters or digits ("-", "<unknown>" after cleanup) counts as no artist.
        val hintArtist = hint.artist?.takeIf { TextNormalizer.normalize(it).isNotEmpty() }
        val artist = hintArtist?.let { artistSimilarity(it, candidate) } ?: 0.0
        val difference = if (hint.durationMs != null && candidate.lengthMs != null) {
            hint.durationMs - candidate.lengthMs
        } else {
            null
        }
        val durationCredit = difference?.let(::durationCredit) ?: 0.0
        val albumTrack = albumTrackAgreement(hint, candidate)

        var total = min(TEXT_CAP, 0.4 * title + 0.3 * artist + 0.2 * durationCredit + 0.1 * albumTrack)
        if (difference != null && abs(difference) > NO_CREDIT_MS) total *= DURATION_PENALTY_FACTOR
        if (hintArtist != null && title >= MATCH && artist < 0.5) total -= ARTIST_PENALTY
        return TextScore(title, artist, durationCredit, albumTrack, total.coerceIn(0.0, 1.0))
    }

    /** 1 up to a difference of 2 s, falling linearly to 0 at 30 s. */
    fun durationCredit(differenceMs: Long): Double {
        val difference = abs(differenceMs)
        return when {
            difference <= FULL_CREDIT_MS -> 1.0
            difference >= NO_CREDIT_MS -> 0.0
            else -> 1.0 - (difference - FULL_CREDIT_MS).toDouble() / (NO_CREDIT_MS - FULL_CREDIT_MS)
        }
    }

    /**
     * The closest of: the whole credit, the candidate's first name against the whole hint (for names such as
     * "Above & Beyond") and against the hint's first name (for a hint that lists several artists).
     */
    private fun artistSimilarity(artist: String, candidate: TextCandidate): Double {
        val whole = Similarity.text(artist, candidate.artistCredit)
        val firstOfCandidate = candidate.artistNames.firstOrNull() ?: candidate.artistCredit
        val wholeHint = Similarity.text(artist, firstOfCandidate)
        val firstOfHint = Spaces.plain(artist).split(artistSeparators).firstOrNull { it.isNotBlank() }
            ?: return max(whole, wholeHint)
        return maxOf(whole, wholeHint, Similarity.text(firstOfHint, firstOfCandidate))
    }

    private fun albumTrackAgreement(hint: TrackHint, candidate: TextCandidate): Double {
        if (hint.album == null && hint.trackNumber == null) return 0.0
        return candidate.releases.maxOfOrNull { release ->
            val album = hint.album != null && Similarity.text(hint.album, release.title) >= MATCH
            val track = hint.trackNumber != null && release.trackNumber == hint.trackNumber
            when {
                album && track -> 1.0
                album || track -> 0.5
                else -> 0.0
            }
        } ?: 0.0
    }
}
