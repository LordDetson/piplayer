package io.github.lorddetson.piplayer.identification.batch

import io.github.lorddetson.piplayer.identification.pipeline.FileRef
import io.github.lorddetson.piplayer.identification.pipeline.FingerprintCache
import io.github.lorddetson.piplayer.identification.pipeline.LocalStage
import io.github.lorddetson.piplayer.identification.pipeline.PipelineResult
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * One album group's phase of a batch, without the WorkManager machinery (TI §8): [scanGroup] is phase (a), the local
 * reads and fingerprints of the group's files; [lookupGroup] is phase (b), one pipeline run per file of the group.
 * Both resolve the group themselves: the batch row by its UUID (a row that is gone after a rebuild, or not in
 * `queued`/`processing`, ends the job), then the batch's files through [grouping], the group at [groupIndex] of them.
 * A finished job enqueues the next job through the [BatchStep] it gives back, and the lookup of the last group moves
 * the batch to review. The functions are idempotent: re-running a phase re-reads files and re-caches fingerprints
 * (a cached fingerprint is kept, a recomputed one replaces its row), and a pipeline run replaces the run before's
 * rows (TI §3 "One run"), so a job re-run after a process death resumes in place. A file that cannot be read or
 * fingerprinted is skipped, as in a single run, and never fails the group.
 */

/** The batch state the phases read and write (TI §8): the batch row, its files and the status transitions. */
interface BatchState {

    /** The batch row, or null (the library was rebuilt and the batch is gone with it, TI §10.1). */
    suspend fun batch(uuid: String): BatchEntity?

    /** The batch's files in their order, with the album data that groups them ([BatchFile]). */
    suspend fun files(uuid: String): List<BatchFile>

    suspend fun setStatus(uuid: String, status: String)
}

/** The batch state of the app, over the library database's `batch` and `batch_file` tables (TI §10.1). */
class RoomBatchState(private val library: LibraryAccess) : BatchState {

    override suspend fun batch(uuid: String): BatchEntity? =
        library.use { database -> runBlocking { database.batches().batch(uuid) } }

    override suspend fun files(uuid: String): List<BatchFile> =
        library.use { database -> runBlocking { database.batches().files(uuid) } }

    override suspend fun setStatus(uuid: String, status: String) {
        library.use { database -> runBlocking { database.batches().setStatus(uuid, status) } }
    }
}

/** What a finished phase decides next (TI §8), carried out by the worker that ran it. */
sealed interface BatchStep {

    /** Nothing ran and nothing is enqueued: the batch is gone or not processing, or the group does not exist. */
    data object Idle : BatchStep

    /** Phase (a) of the group is done: the worker enqueues phase (b) of the same group. */
    data object LookupSame : BatchStep

    /** Phase (b) of the group is done: the worker enqueues phase (a) of the group [nextGroup]. */
    data class ScanNext(val nextGroup: Int) : BatchStep

    /** Every group is done and the batch's status is `review` already: the worker enqueues nothing. */
    data object Review : BatchStep
}

/** The group a worker is on, with the number of groups of the batch; or why there is none to work on. */
private sealed interface Resolved {
    class Group(val group: BatchGroup, val count: Int) : Resolved

    /** The batch has no files: it waits in review, nothing to do. */
    data object Review : Resolved

    data object Idle : Resolved
}

/** The group at [groupIndex] of the batch's files, moving a `queued` batch to `processing` and an empty one to review. */
private suspend fun resolve(
    state: BatchState,
    batchUuid: String,
    groupIndex: Int,
    grouping: (List<BatchFile>) -> List<BatchGroup>,
): Resolved {
    val row = state.batch(batchUuid) ?: return Resolved.Idle
    when (row.status) {
        // A job is running, so the batch is processing even when its starter left it queued.
        BatchEntity.STATUS_QUEUED -> state.setStatus(batchUuid, BatchEntity.STATUS_PROCESSING)
        BatchEntity.STATUS_PROCESSING -> Unit
        else -> return Resolved.Idle
    }
    val files = state.files(batchUuid)
    if (files.isEmpty()) {
        state.setStatus(batchUuid, BatchEntity.STATUS_REVIEW)
        return Resolved.Review
    }
    val groups = grouping(files)
    val group = groups.getOrNull(groupIndex) ?: return Resolved.Idle
    return Resolved.Group(group, groups.size)
}

/**
 * Phase (a) of one album group (TI §8): stage 0 reads each file and its fingerprint is cached when none is, then the
 * worker enqueues phase (b) ([BatchStep.LookupSame]). No network. A file that cannot be read has no content key and
 * is skipped; one that cannot be decoded for a fingerprint is skipped too, and its text search still runs in phase
 * (b). The fingerprints are decoded on the pipeline's user lane until M4a step 5 gives batches a background lane
 * (TODO M4a step 5, TI §3.2 "Threads"). Reports each finished file through [onProgress] (done, total).
 */
suspend fun scanGroup(
    batchUuid: String,
    groupIndex: Int,
    state: BatchState,
    local: LocalStage,
    fingerprints: FingerprintCache,
    grouping: (List<BatchFile>) -> List<BatchGroup> = ::groupByAlbum,
    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): BatchStep {
    val resolved = resolve(state, batchUuid, groupIndex, grouping)
    if (resolved !is Resolved.Group) return if (resolved is Resolved.Review) BatchStep.Review else BatchStep.Idle
    val files = resolved.group.files
    files.forEachIndexed { index, file ->
        try {
            val read = local.read(FileRef.Library(file.fileId))
            val key = read?.target?.contentKey
            if (key != null && fingerprints.cached(key) == null) fingerprints.compute(read)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // One file that cannot be read or fingerprinted does not fail the group (TI §3.2).
        }
        onProgress(index + 1, files.size)
    }
    return BatchStep.LookupSame
}

/**
 * Phase (b) of one album group (TI §8): [run] identifies each file, with [batchUuid], so the fingerprints phase (a)
 * cached are looked up and each run keeps its candidates, trace and lookup job under the batch's UUID (TI §10.1).
 * The group's high band is then prepared on its own ([BatchAutoApply]), while the medium band and "no match" stay
 * cards of the review. Then the worker enqueues phase (a) of the next group, and after the last group the batch moves to
 * `review` ([BatchStep.Review]). A run that ends without sending or storing anything still counts as done for the group,
 * so one unavailable provider does not stall the batch. Reports each finished file through [onProgress] (done, total).
 */
suspend fun lookupGroup(
    batchUuid: String,
    groupIndex: Int,
    state: BatchState,
    run: suspend (FileRef, String) -> PipelineResult,
    grouping: (List<BatchFile>) -> List<BatchGroup> = ::groupByAlbum,
    prepare: BatchPreparation? = null,
    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
): BatchStep {
    val resolved = resolve(state, batchUuid, groupIndex, grouping)
    if (resolved !is Resolved.Group) return if (resolved is Resolved.Review) BatchStep.Review else BatchStep.Idle
    val group = resolved.group
    val results = group.files.mapIndexed { index, file ->
        val result = run(FileRef.Library(file.fileId), batchUuid)
        onProgress(index + 1, group.files.size)
        result
    }
    if (prepare != null) BatchAutoApply.prepare(batchUuid, results, prepare)
    return if (groupIndex + 1 < resolved.count) {
        BatchStep.ScanNext(groupIndex + 1)
    } else {
        state.setStatus(batchUuid, BatchEntity.STATUS_REVIEW)
        BatchStep.Review
    }
}
