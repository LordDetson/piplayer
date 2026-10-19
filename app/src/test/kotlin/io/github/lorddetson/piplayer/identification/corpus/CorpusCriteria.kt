package io.github.lorddetson.piplayer.identification.corpus

import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.Brackets
import io.github.lorddetson.piplayer.identification.text.Similarity
import io.github.lorddetson.piplayer.identification.text.VersionSuffix
import kotlin.math.abs

/**
 * Automatic judgement of a corpus result, chosen by the owner: the top candidate is right when title and artist are
 * at least 0.9 similar and the lengths differ by at most 5 s.
 *
 * - Strict: the whole titles as written are compared.
 * - Lenient: only the names outside a version description are compared, so "Girlfriend Radio Edit" matches
 *   "Girlfriend"; the version itself is not checked.
 */
object CorpusCriteria {

    private const val MATCH = 0.9
    private const val MAX_DURATION_DIFFERENCE_MS = 5_000L
    private val creditSeparators =
        Regex("""\s*(?:,|&|;|\bfeaturing\b|\bfeat\b\.?|\bft\b\.?)\s*""", RegexOption.IGNORE_CASE)

    fun strict(track: LikedTrack, title: String, credit: String, lengthMs: Long?): Boolean =
        Similarity.text(track.title, title) >= MATCH && sameArtistAndLength(track, credit, lengthMs)

    fun lenient(track: LikedTrack, title: String, credit: String, lengthMs: Long?): Boolean =
        Similarity.text(name(track.title), name(title)) >= MATCH && sameArtistAndLength(track, credit, lengthMs)

    /** The artist similarity the scorer gives, with the credit's names split at the usual separators. */
    fun artistSimilarity(artist: String, credit: String): Double {
        val names = credit.split(creditSeparators).filter { it.isNotBlank() }
        val candidate = TextCandidate("", "", credit, names, null)
        return TextCandidateScorer.score(TrackHint(title = null, artist = artist, durationMs = null), candidate).artist
    }

    private fun sameArtistAndLength(track: LikedTrack, credit: String, lengthMs: Long?): Boolean {
        val duration = track.durationMs ?: return false
        val length = lengthMs ?: return false
        return artistSimilarity(track.artist, credit) >= MATCH && abs(duration - length) <= MAX_DURATION_DIFFERENCE_MS
    }

    private fun name(title: String): String = Brackets.split(VersionSuffix.bracket(title)).first
}
