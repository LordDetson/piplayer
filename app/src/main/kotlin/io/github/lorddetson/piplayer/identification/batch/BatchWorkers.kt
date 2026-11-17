package io.github.lorddetson.piplayer.identification.batch

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.lorddetson.piplayer.identification.pipeline.AppPipeline
import io.github.lorddetson.piplayer.identification.pipeline.AndroidLocalStage
import io.github.lorddetson.piplayer.identification.pipeline.LibraryFingerprintCache
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.tagging.Tagger
import kotlinx.coroutines.flow.first

/**
 * The WorkManager side of the batch phases (TI §8): one job per album group, [BatchScanWorker] for phase (a) and
 * [BatchLookupWorker] for phase (b), chained scan then lookup then the next group's scan, so one finished job
 * enqueues the next one. A job's `Data` carries only the batch UUID and the group's index; the worker resolves the
 * group itself from the batch's files ([BatchPhases]). Jobs are idempotent and resumable: their state lives in the
 * `batch` and `lookup_job` tables and in WorkManager's own queue, and a re-run after a process death resumes in place
 * ([BatchPhases]). No worker calls `setForeground`: each group finishes well inside the 10-minute limit (TI §8).
 * All jobs of a batch carry its UUID as their tag, so [BatchQueue.cancel] stops a whole batch and a library rebuild,
 * which drops the `batch` row, makes the queued jobs end as [BatchStep.Idle] on their own.
 */

/** The keys and helpers of a batch job's WorkManager `Data` (TI §8): IDs only, never the files themselves. */
object BatchData {

    /** The batch's UUID. */
    const val BATCH_UUID = "batch_uuid"

    /** The group's index among the batch's groups, as [groupByAlbum] orders them. */
    const val GROUP_INDEX = "group_index"

    /** How many files of the group a job has finished and how many the group has, as its progress. */
    const val FILES_DONE = "files_done"
    const val FILES_TOTAL = "files_total"

    /** The input of a job for group [groupIndex] of [batchUuid]. */
    fun input(batchUuid: String, groupIndex: Int): Data =
        workDataOf(BATCH_UUID to batchUuid, GROUP_INDEX to groupIndex)

    /** The progress of a job that has finished [done] of [total] files of its group. */
    fun progress(done: Int, total: Int): Data = workDataOf(FILES_DONE to done, FILES_TOTAL to total)
}

/** The job name of phase [phase] of group [groupIndex] of a batch, unique per job. */
private fun batchJobName(batchUuid: String, phase: String, groupIndex: Int) = "batch-$phase-$batchUuid-$groupIndex"

/** The WorkManager queue of a batch's jobs (TI §8): start, the chain's enqueues and the whole batch's cancel. */
class BatchQueue(private val context: Context, private val workManager: WorkManager = WorkManager.getInstance(context)) {

    /**
     * Starts the batch: enqueues phase (a) of its first group. The caller has made the batch row and its files;
     * the first job moves the status from `queued` to `processing` ([BatchPhases]).
     */
    fun start(batchUuid: String) = enqueueScan(batchUuid, groupIndex = 0)

    /** Enqueues phase (a) of group [groupIndex]; battery not low, no network ([scanConstraints]). */
    fun enqueueScan(batchUuid: String, groupIndex: Int) {
        val request = OneTimeWorkRequestBuilder<BatchScanWorker>()
            .setInputData(BatchData.input(batchUuid, groupIndex))
            .setConstraints(scanConstraints())
            .addTag(batchUuid)
            .build()
        workManager.enqueueUniqueWork(batchJobName(batchUuid, "scan", groupIndex), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enqueues phase (b) of group [groupIndex], with the network type of the batch settings (TI §8). Only the
     * unmetered-only setting exists in M4a step 2; the one-off "Use mobile data this time" and roaming answers come
     * with the Fix tab (TODO M4a step 6).
     */
    suspend fun enqueueLookup(batchUuid: String, groupIndex: Int) {
        val unmeteredOnly = IdentificationSettings(context).batchUnmeteredOnly.first()
        val request = OneTimeWorkRequestBuilder<BatchLookupWorker>()
            .setInputData(BatchData.input(batchUuid, groupIndex))
            .setConstraints(lookupConstraints(unmeteredOnly = unmeteredOnly))
            .addTag(batchUuid)
            .build()
        workManager.enqueueUniqueWork(batchJobName(batchUuid, "lookup", groupIndex), ExistingWorkPolicy.KEEP, request)
    }

    /** Cancels every queued and running job of the batch; the caller marks the batch `cancelled` (TI §8). */
    fun cancel(batchUuid: String) = workManager.cancelAllWorkByTag(batchUuid)
}

/** What both workers share: the input from `Data`, the batch state and the step the finished phase decided. */
abstract class BatchWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    /** The batch UUID and the group index from the input; null when the input is not a batch job's. */
    protected fun args(): Pair<String, Int>? {
        val uuid = inputData.getString(BatchData.BATCH_UUID) ?: return null
        val index = inputData.getInt(BatchData.GROUP_INDEX, -1)
        return if (index < 0) null else uuid to index
    }

    protected fun batchState() = RoomBatchState(LibraryAccess.app(applicationContext))

    protected suspend fun report(done: Int, total: Int) = setProgress(BatchData.progress(done, total))

    /** Runs [phase] for the job's group and enqueues the next job of the chain, or nothing (TI §8). */
    protected suspend fun runPhase(phase: suspend (String, Int) -> BatchStep): Result {
        val (batchUuid, groupIndex) = args() ?: return Result.failure()
        return when (val step = phase(batchUuid, groupIndex)) {
            BatchStep.Idle, BatchStep.Review -> Result.success()
            BatchStep.LookupSame -> {
                BatchQueue(applicationContext).enqueueLookup(batchUuid, groupIndex)
                Result.success()
            }
            is BatchStep.ScanNext -> {
                BatchQueue(applicationContext).enqueueScan(batchUuid, step.nextGroup)
                Result.success()
            }
        }
    }
}

/**
 * Phase (a) of one album group (TI §8): the local reads and fingerprints through the pipeline's own stage 0 and
 * fingerprint cache, no network. The worker ends with the next job (phase b of the same group) enqueued.
 */
class BatchScanWorker(appContext: Context, params: WorkerParameters) : BatchWorker(appContext, params) {

    override suspend fun doWork(): Result = runPhase { batchUuid, groupIndex ->
        val context = applicationContext
        // A batch reads and decodes on its own background lane and waits while the gate holds it (TI §8).
        Library.passGate.awaitBatchOpen()
        val local = AndroidLocalStage(context, Tagger.client(context), LibraryAccess.app(context))
        val fingerprints = LibraryFingerprintCache.batch(context, LibraryAccess.app(context))
        scanGroup(batchUuid, groupIndex, batchState(), local, fingerprints, onProgress = ::report)
    }
}

/**
 * Phase (b) of one album group (TI §8): one pipeline run per file, with the batch's UUID, so the cached fingerprints
 * of phase (a) are looked up and each run stores its rows under the batch. The group's high band is prepared on its
 * own there, as Apply decisions of the batch, and the rest waits for the review's cards. The worker ends with the next
 * group's phase (a) enqueued, or with the batch in review after the last group.
 */
class BatchLookupWorker(appContext: Context, params: WorkerParameters) : BatchWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        runPhase { batchUuid, groupIndex ->
            // The app's pipeline shares the process's services, limiters and stores with single-file runs (TI §8).
            val pipeline = AppPipeline.of(applicationContext)
            lookupGroup(
                batchUuid,
                groupIndex,
                batchState(),
                run = { file, batch -> pipeline.run(file, batchUuid = batch) },
                prepare = LibraryBatchPreparation(applicationContext),
                onProgress = ::report,
            )
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        // A transient failure (the database closed by a rebuild, say) retries through WorkManager's backoff.
        Log.w(TAG, "The lookup of a batch group failed", e)
        Result.retry()
    }

    private companion object {
        const val TAG = "BatchLookupWorker"
    }
}
