package io.github.lorddetson.piplayer.identification.pipeline

import io.github.lorddetson.piplayer.identification.release.ReleaseChoice
import io.github.lorddetson.piplayer.identification.score.CandidateRelease
import io.github.lorddetson.piplayer.identification.score.HintField
import io.github.lorddetson.piplayer.identification.score.Ranking
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.identification.score.RecordingMatch
import io.github.lorddetson.piplayer.identification.score.Source
import io.github.lorddetson.piplayer.identification.score.TextCandidate
import io.github.lorddetson.piplayer.identification.score.Tier
import io.github.lorddetson.piplayer.library.db.CandidateEntity
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The candidate rows of a run (TI §10.1): its ranked recordings, best first, and back. `reason` and `payload_json` are
 * JSON of their own small types, so that renaming a class of the scoring never changes what is stored.
 */
object StoredCandidates {

    /** Candidates kept per run. */
    const val LIMIT = 10

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The rows of [ranking] for [contentKey], at most [LIMIT]: the best with the suggested edition of [choice], the others
     * with the top of their own release ranking when release choice browsed them.
     */
    fun rows(contentKey: String, ranking: Ranking, choice: ReleaseChoice): List<CandidateEntity> =
        ranking.matches.take(LIMIT).mapIndexed { position, match ->
            val release = if (position == 0 && choice.suggested != null) {
                choice.suggested.release.id
            } else {
                choice.ranked[match.recordingId]?.firstOrNull()?.release?.id
            }
            CandidateEntity(
                contentKey = contentKey,
                position = position,
                source = match.reason.source.name.lowercase(Locale.ROOT),
                recordingMbid = match.recordingId,
                releaseMbid = release,
                score = match.score,
                reason = json.encodeToString(StoredReason.serializer(), StoredReason.of(match.reason)),
                payloadJson = json.encodeToString(StoredRecording.serializer(), StoredRecording.of(match.candidate)),
            )
        }

    /** The recordings of [rows], in position order, as they were ranked; a row that does not read is left out. */
    fun matches(rows: List<CandidateEntity>): List<RecordingMatch> = rows.sortedBy { it.position }.mapNotNull { row ->
        try {
            val recording = json.decodeFromString(StoredRecording.serializer(), row.payloadJson ?: return@mapNotNull null)
            val reason = json.decodeFromString(StoredReason.serializer(), row.reason ?: return@mapNotNull null)
            RecordingMatch(recording.candidate(row.recordingMbid ?: return@mapNotNull null), row.score, reason.reason())
        } catch (e: IllegalArgumentException) {
            // Kotlin serialization's errors, SerializationException among them, are of this type.
            null
        }
    }

    @Serializable
    private data class StoredRecording(
        val title: String,
        val artistCredit: String,
        val artistNames: List<String>,
        val lengthMs: Long? = null,
        val releases: List<StoredRelease> = emptyList(),
    ) {
        fun candidate(recordingId: String) = TextCandidate(
            recordingId = recordingId,
            title = title,
            artistCredit = artistCredit,
            artistNames = artistNames,
            lengthMs = lengthMs,
            releases = releases.map { CandidateRelease(it.title, it.trackNumber) },
        )

        companion object {
            fun of(candidate: TextCandidate) = StoredRecording(
                title = candidate.title,
                artistCredit = candidate.artistCredit,
                artistNames = candidate.artistNames,
                lengthMs = candidate.lengthMs,
                releases = candidate.releases.map { StoredRelease(it.title, it.trackNumber) },
            )
        }
    }

    @Serializable
    private data class StoredRelease(val title: String, val trackNumber: Int? = null)

    @Serializable
    private data class StoredReason(
        val source: String,
        val tier: String,
        val foundBy: List<String>,
        val fingerprintScore: Double? = null,
        val durationDeltaMs: Long? = null,
        val title: Double? = null,
        val artist: Double? = null,
        val disagreement: List<String> = emptyList(),
    ) {
        fun reason() = Reason(
            source = Source.valueOf(source),
            tier = Tier.valueOf(tier),
            foundBy = foundBy.mapTo(linkedSetOf()) { Source.valueOf(it) },
            fingerprintScore = fingerprintScore,
            durationDeltaMs = durationDeltaMs,
            title = title,
            artist = artist,
            disagreement = disagreement.mapTo(linkedSetOf()) { HintField.valueOf(it) },
        )

        companion object {
            fun of(reason: Reason) = StoredReason(
                source = reason.source.name,
                tier = reason.tier.name,
                foundBy = reason.foundBy.map { it.name },
                fingerprintScore = reason.fingerprintScore,
                durationDeltaMs = reason.durationDeltaMs,
                title = reason.title,
                artist = reason.artist,
                disagreement = reason.disagreement.map { it.name },
            )
        }
    }
}
