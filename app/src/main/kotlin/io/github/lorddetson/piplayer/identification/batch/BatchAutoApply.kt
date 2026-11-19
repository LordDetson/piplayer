package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationOutcome
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.identification.contract.Provenance
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.identification.score.Band
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The automatic preparation of a group's high band (TI §8, the owner's decision of 17.11.2026): a result that stands in the
 * high band with its chosen release is stored as an Apply decision with the batch's UUID, without the user, while the
 * medium band and "no match" stay cards of the review. An automatic Apply takes the release's values the way a ticked card
 * does, but it fetches no cover, which would need an image of the Cover Art Archive and a saved cover for it, and it never
 * clears a compilation flag of a file.
 */

/** What a group's results divide into (TI §8). */
class Prepared(
    /** The Apply requests the app makes on its own, in the group's order. */
    val automatic: List<ApplyRequest>,
    /** The results that wait for the user's cards, in the group's order. */
    val review: List<PipelineResult>,
)

object BatchAutoApply {

    /** The group's results in the band [Band.HIGH] prepared as [ApplyRequest]s, and the rest, as their cards (TI §8). */
    fun divide(results: List<PipelineResult>, protected: Set<ProtectedField>, at: Instant): Prepared {
        val automatic = mutableListOf<ApplyRequest>()
        val review = mutableListOf<PipelineResult>()
        for (result in results) {
            val request = request(result, protected, at)
            if (request == null) review += result else automatic += request
        }
        return Prepared(automatic, review)
    }

    /**
     * The Apply request an automatic preparation makes of [result], or null when the result waits for a card (TI §8): the
     * run found the file in the high band with its chosen release, the release holds the winner's own recording, and at
     * least one field is left to decide after the user's protected fields (TI §6).
     */
    fun request(result: PipelineResult, protected: Set<ProtectedField>, at: Instant): ApplyRequest? {
        val found = result.outcome as? IdentificationOutcome.Found ?: return null
        if (found.band != Band.HIGH) return null
        // A batch holds files of the library, and only they are applied by a file ID.
        val target = result.file?.target as? IdentificationTarget.LibraryFile ?: return null
        val edition = found.choice.suggested ?: return null
        val winner = found.choice.recordingId?.let { id -> found.ranking.matches.firstOrNull { it.recordingId == id } } ?: return null
        // Another recording at the song's place is the user's choice, not the run's (TI §4.2 step 6).
        if (edition.details.recordingId != winner.recordingId) return null
        val fields = fields(edition.details, protected)
        if (fields.isEmpty()) return null
        val provenance = Provenance.of(winner, edition.details, found.acoustIdTrackIds[winner.recordingId], at)
        return ApplyRequest(target, edition.details, fields, cover = null, provenance)
    }

    /**
     * The fields an automatic Apply decides (TI §8): every field the release gives a value for, without the cover, without
     * the fields the user protects (TI §6), and without a compilation flag that says "not a compilation", which would
     * clear a flag a file holds. A field is taken as a whole, with all its parts (TI §7.1).
     */
    fun fields(details: ReleaseDetails, protected: Set<ProtectedField>): Set<ProtectedField> {
        val values = DecisionContent.ofRelease(details)
        return ProtectedField.identificationFields.filterTo(LinkedHashSet()) { field ->
            field != ProtectedField.COVER && field !in protected && decided(details, values, field)
        }
    }

    private fun decided(details: ReleaseDetails, values: Map<DecidedField, String?>, field: ProtectedField): Boolean =
        if (field == ProtectedField.COMPILATION) {
            details.compilation
        } else {
            DecidedField.partsOf(field).any { !values[it].isNullOrEmpty() }
        }

    /**
     * Prepares the group's high band through [preparation] (TI §8), each automatic result with the batch's UUID; the
     * results that wait for a card are returned. A decision that cannot be written leaves its track to its card, since
     * no decision stands for it, and never fails the group.
     */
    suspend fun prepare(batchUuid: String, results: List<PipelineResult>, preparation: BatchPreparation): Prepared {
        val prepared = divide(results, preparation.fields(), preparation.now())
        for (request in prepared.automatic) {
            try {
                preparation.apply(request, batchUuid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The track keeps its card: nothing of the batch decides it.
            }
        }
        return prepared
    }
}

/**
 * How phase (b) prepares a group's high band on its own (TI §8): the app stores the decisions, a test records them.
 */
interface BatchPreparation {

    /** The fields the user protects now (TI §6), which no automatic Apply takes. */
    suspend fun fields(): Set<ProtectedField>

    /** When the group's run ended, the `fetched_at` of its decisions' provenance (TI §7.1). */
    fun now(): Instant

    /** Stores [request] as a decision of [batchUuid]; the decision's ID, or null when nothing was written. */
    suspend fun apply(request: ApplyRequest, batchUuid: String): Long?
}
