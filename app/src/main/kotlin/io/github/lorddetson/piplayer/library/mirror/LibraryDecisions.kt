package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.Decisions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The decisions as the app makes them (TI §7.1, §10.3): each commits to the user database first, then mirrors the track's
 * files into the library database before it returns, so lists, Now Playing and the session show the result at once. A
 * caller cancelled meanwhile stops neither: the commit finishes, and the mirror runs in its own scope. A failed mirror
 * asks for a whole run, and a process that dies between the two is repaired by the start-up mirror.
 */
class LibraryDecisions(private val decisions: Decisions, private val mirror: () -> DecisionMirror) {

    /** `Decisions.apply`, then the mirror of the track; null when nothing was written. */
    suspend fun apply(
        request: ApplyRequest,
        savedCoverId: Long? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
    ): Decisions.Committed? = committed { decisions.apply(request, savedCoverId, atDecision) }

    /** `Decisions.edit`, then the mirror of the track. */
    suspend fun edit(
        identityId: Long,
        values: Map<DecidedField, String?>,
        contentKey: ByteArray? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
    ): Decisions.Committed? = committed { decisions.edit(identityId, values, contentKey, atDecision) }

    /** `Decisions.revert`, then the mirror of the track. */
    suspend fun revert(identityId: Long, fields: Set<ProtectedField>, contentKey: ByteArray? = null): Decisions.Committed? =
        committed { decisions.revert(identityId, fields, contentKey) }

    /** `Decisions.keepAsIs`, then the mirror of the track. */
    suspend fun keepAsIs(identityId: Long, contentKey: ByteArray? = null): Decisions.Committed? =
        committed { decisions.keepAsIs(identityId, contentKey) }

    /**
     * `Decisions.skip` with the stamp of the file it is made on as the library keeps it, [fileMtimeSeconds] in seconds
     * (`audio_file.mtime`), then the mirror of the track.
     */
    suspend fun skip(identityId: Long, fileSize: Long, fileMtimeSeconds: Long, contentKey: ByteArray? = null): Decisions.Committed? =
        committed { decisions.skip(identityId, fileSize, SkipLapse.mtimeMs(fileMtimeSeconds), contentKey) }

    /** `Decisions.undo`, then the mirror of the track. */
    suspend fun undo(decisionId: Long): Decisions.Committed? = committed { decisions.undo(decisionId) }

    /** "Undo identification" (TI §7.1): undoes the track's newest identification that is not undone, if any. */
    suspend fun undoIdentification(identityId: Long): Decisions.Committed? =
        decisions.newestIdentification(identityId)?.let { undo(it) }

    /** [commit], which a cancelled caller does not interrupt, then the mirror of what it wrote. */
    private suspend fun committed(commit: suspend () -> Decisions.Committed?): Decisions.Committed? {
        val committed = withContext(NonCancellable) { commit() } ?: return null
        try {
            mirror().mirrorCommitted(committed)
        } catch (e: CancellationException) {
            // A cancelled caller goes; a mirror closed by a reopen leaves the run to the next library's start-up run.
            currentCoroutineContext().ensureActive()
        }
        return committed
    }
}
