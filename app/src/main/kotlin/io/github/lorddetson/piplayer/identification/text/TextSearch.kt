package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.musicbrainz.MusicBrainzException
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.TextCandidateScorer
import io.github.lorddetson.piplayer.identification.score.TextScore
import io.github.lorddetson.piplayer.identification.score.TrackHint
import java.io.IOException

/** Runs one recording search query. */
fun interface RecordingSearch {
    suspend fun search(query: RecordingQuery): List<TextCandidate>
}

data class ScoredCandidate(val candidate: TextCandidate, val score: TextScore)

/** One variant that was sent, with its candidates in descending score. */
data class VariantAttempt(val query: RecordingQuery, val candidates: List<ScoredCandidate>)

/**
 * Every variant tried and the merged candidates, best first; a recording found twice keeps its higher score. When a
 * later variant failed after earlier ones were answered, [failure] says why the search stopped early.
 */
data class TextSearchResult(
    val attempts: List<VariantAttempt>,
    val candidates: List<ScoredCandidate>,
    val failure: IOException? = null,
)

/**
 * Stage 4 of identification (TI §3.3): sends the query variants in order and stops at the first one that yields a
 * candidate scoring at least [STOP_SCORE]. When a later variant fails, the candidates found so far are kept; a failure
 * of the first variant, or a rejected User-Agent at any point, is thrown.
 */
class TextSearch(
    private val search: RecordingSearch,
    private val options: QueryOptions = QueryOptions(),
) {

    suspend fun search(original: TrackHint, limit: Int = TextQueryBuilder.AUTOMATIC_LIMIT): TextSearchResult {
        val hint = if (options.bracketVersion) original.copy(title = original.title?.let(VersionSuffix::bracket)) else original
        val attempts = mutableListOf<VariantAttempt>()
        var failure: IOException? = null
        for (query in TextQueryBuilder.build(hint, limit, options)) {
            val found = try {
                search.search(query)
            } catch (e: MusicBrainzException.UserAgentRejected) {
                throw e
            } catch (e: IOException) {
                if (attempts.isEmpty()) throw e
                failure = e
                break
            }
            val scored = found
                .map { ScoredCandidate(it, TextCandidateScorer.score(hint, it)) }
                .sortedByDescending { it.score.total }
            attempts += VariantAttempt(query, scored)
            if ((scored.firstOrNull()?.score?.total ?: 0.0) >= STOP_SCORE) break
        }
        val merged = attempts.flatMap { it.candidates }
            .groupBy { it.candidate.recordingId }
            .map { (_, copies) -> copies.maxBy { it.score.total } }
            .sortedByDescending { it.score.total }
        return TextSearchResult(attempts, merged, failure)
    }

    companion object {
        const val STOP_SCORE = 0.50
    }
}
