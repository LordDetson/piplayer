package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.library.db.CandidateEntity

/** The candidate rows of a run (TI §10.1): its ranked recordings, best first, and back. */
object StoredCandidates {

    /** Candidates kept per run. */
    const val LIMIT = 10

    /** The rows of [ranking] for [contentKey], at most [LIMIT], with the release [choice] made. */
    fun rows(contentKey: String, ranking: Ranking, choice: ReleaseChoice): List<CandidateEntity> = emptyList()

    /** The recordings of [rows], in position order, as they were ranked. */
    fun matches(rows: List<CandidateEntity>): List<RecordingMatch> = emptyList()
}
