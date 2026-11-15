package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.Decisions

/**
 * The decisions as the app makes them (TI §7.1, §10.3): each commits to the user database first, then mirrors the track's
 * files into the library database before it returns, so lists, Now Playing and the session show the result at once. A
 * process that dies between the two is repaired by the start-up mirror.
 */
class LibraryDecisions(private val decisions: Decisions, private val mirror: () -> DecisionMirror) {

    /** `Decisions.apply`, then the mirror of the track; null when nothing was written. */
    suspend fun apply(
        request: ApplyRequest,
        savedCoverId: Long? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
    ): Decisions.Committed? = decisions.apply(request, savedCoverId, atDecision)?.also { mirrored(it) }

    /** `Decisions.edit`, then the mirror of the track. */
    suspend fun edit(
        identityId: Long,
        values: Map<DecidedField, String?>,
        contentKey: ByteArray? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
    ): Decisions.Committed? = decisions.edit(identityId, values, contentKey, atDecision)?.also { mirrored(it) }

    /** `Decisions.revert`, then the mirror of the track. */
    suspend fun revert(identityId: Long, fields: Set<ProtectedField>, contentKey: ByteArray? = null): Decisions.Committed? =
        decisions.revert(identityId, fields, contentKey)?.also { mirrored(it) }

    /** `Decisions.undo`, then the mirror of the track. */
    suspend fun undo(decisionId: Long): Decisions.Committed? = decisions.undo(decisionId)?.also { mirrored(it) }

    /** "Undo identification" (TI §7.1): undoes the track's newest identification that is not undone, if any. */
    suspend fun undoIdentification(identityId: Long): Decisions.Committed? =
        decisions.newestIdentification(identityId)?.let { undo(it) }

    private suspend fun mirrored(committed: Decisions.Committed) {
        mirror().mirrorIdentities(listOf(committed.identityId), committed.counter)
    }
}
