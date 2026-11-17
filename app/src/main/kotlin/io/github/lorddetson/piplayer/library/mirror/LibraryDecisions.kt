package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.identity.ApplyFiles
import io.github.lorddetson.piplayer.library.identity.Hex
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
 * asks for a whole run, and a process that dies between the two is repaired by the start-up mirror. [files] gives a
 * library target that has no identity yet its file, for the identity Apply makes (TI §10.2 "An identity at Apply").
 * A decision made while a batch's card opened the review takes the batch's UUID (TI §8), which its row keeps as 16 bytes.
 */
class LibraryDecisions(
    private val decisions: Decisions,
    private val files: ApplyFiles = ApplyFiles.NONE,
    private val mirror: () -> DecisionMirror,
) {

    /** `Decisions.apply`, then the mirror of the track; null when nothing was written. */
    suspend fun apply(
        request: ApplyRequest,
        savedCoverId: Long? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
        batchUuid: String? = null,
    ): Decisions.Committed? = committed(batchUuid) {
        // Only for a target without an identity, and before the user database's transaction: the library's lock and the
        // user database's are never held at once. An identity made meanwhile is used all the same.
        val target = request.target
        val file = if (target is IdentificationTarget.LibraryFile && target.startMs == 0L && !decisions.hasIdentity(target)) {
            files.file(target.fileId, target.contentKey)
        } else {
            null
        }
        // The identity seen may be gone by the transaction (a takeover of the attach pass): then the file is read after all.
        decisions.apply(request, savedCoverId, atDecision, file, batchBytes(batchUuid))
            ?: if (file == null && target is IdentificationTarget.LibraryFile && target.startMs == 0L) {
                decisions.apply(request, savedCoverId, atDecision, files.file(target.fileId, target.contentKey), batchBytes(batchUuid))
            } else {
                null
            }
    }

    /** `Decisions.edit`, then the mirror of the track. */
    suspend fun edit(
        identityId: Long,
        values: Map<DecidedField, String?>,
        contentKey: ByteArray? = null,
        atDecision: List<Decisions.LocationValues> = emptyList(),
        batchUuid: String? = null,
    ): Decisions.Committed? = committed(batchUuid) { decisions.edit(identityId, values, contentKey, atDecision, batchBytes(batchUuid)) }

    /** `Decisions.revert`, then the mirror of the track. */
    suspend fun revert(identityId: Long, fields: Set<ProtectedField>, contentKey: ByteArray? = null): Decisions.Committed? =
        committed(null) { decisions.revert(identityId, fields, contentKey) }

    /** `Decisions.keepAsIs`, then the mirror of the track. */
    suspend fun keepAsIs(identityId: Long, contentKey: ByteArray? = null, batchUuid: String? = null): Decisions.Committed? =
        committed(batchUuid) { decisions.keepAsIs(identityId, contentKey, batchBytes(batchUuid)) }

    /**
     * `Decisions.skip` with the stamp of the file it is made on as the library keeps it, [fileMtimeSeconds] in seconds
     * (`audio_file.mtime`), then the mirror of the track.
     */
    suspend fun skip(
        identityId: Long,
        fileSize: Long,
        fileMtimeSeconds: Long,
        contentKey: ByteArray? = null,
        batchUuid: String? = null,
    ): Decisions.Committed? =
        committed(batchUuid) { decisions.skip(identityId, fileSize, SkipLapse.mtimeMs(fileMtimeSeconds), contentKey, batchBytes(batchUuid)) }

    /** `Decisions.undo`, then the mirror of the track. */
    suspend fun undo(decisionId: Long): Decisions.Committed? = committed(null) { decisions.undo(decisionId) }

    /** "Undo identification" (TI §7.1): undoes the track's newest identification that is not undone, if any. */
    suspend fun undoIdentification(identityId: Long): Decisions.Committed? =
        decisions.newestIdentification(identityId)?.let { undo(it) }

    /** [commit], which a cancelled caller does not interrupt, then the mirror of what it wrote. */
    private suspend fun committed(batchUuid: String?, commit: suspend () -> Decisions.Committed?): Decisions.Committed? {
        // Read before the commit, so that a UUID that is not one fails without writing anything.
        batchBytes(batchUuid)
        val committed = withContext(NonCancellable) { commit() } ?: return null
        try {
            mirror().mirrorCommitted(committed)
        } catch (e: CancellationException) {
            // A cancelled caller goes; a mirror closed by a reopen leaves the run to the next library's start-up run.
            currentCoroutineContext().ensureActive()
        }
        return committed
    }

    /** The 16 bytes of a batch's UUID (TI §8), null for a single-file decision. */
    private fun batchBytes(batchUuid: String?): ByteArray? =
        batchUuid?.let { requireNotNull(Hex.mbidBytes(it)) { "Not a batch UUID: $it" } }
}
