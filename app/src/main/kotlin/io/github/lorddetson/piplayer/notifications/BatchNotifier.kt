package io.github.lorddetson.piplayer.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.lorddetson.piplayer.identification.batch.BatchData
import io.github.lorddetson.piplayer.identification.batch.RoomBatchState
import io.github.lorddetson.piplayer.identification.batch.groupByAlbum
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the batch's notification in step with its WorkManager jobs (TI §8, PIM-21): the progress of the batch started
 * last, then the notification that opens the Fix tab when the batch reaches review or done. It is no service and
 * changes nothing in the batch: it reads the batch's album groups once, watches the progress the workers report
 * (`BatchData.FILES_DONE` and `FILES_TOTAL`) and posts what [BatchNotifications] builds. When the user allows no
 * notifications it costs the batch nothing but the posts, and the Fix tab shows the same progress.
 *
 * The notification follows one batch, the one started last: two batches share the one notification otherwise, and the
 * batch started last is the one the user just asked for. A batch that outlives the process, which WorkManager runs on,
 * gets its notification back when the app starts ([resume]).
 */
class BatchNotifier private constructor(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    /** Follows [batchUuid] and shows its progress from now on, instead of the batch followed before. */
    fun started(batchUuid: String) {
        watching?.cancel()
        watching = scope.launch { follow(batchUuid) }
    }

    /**
     * Follows the batch that was processing when the process ended, if there is one (TI §8): a batch of hours outlives
     * the app, and its notification comes back with the app. The library is read off the main thread.
     */
    fun resume() {
        scope.launch {
            val uuid = withContext(Dispatchers.IO) { lastProcessing() } ?: return@launch
            started(uuid)
        }
    }

    private suspend fun follow(batchUuid: String): Unit = try {
        val sizes = withContext(Dispatchers.IO) { groupSizes(batchUuid) } ?: return
        val meter = EtaMeter()
        repeat(ROUNDS) {
            val sawJobs = watch(batchUuid, sizes, meter)
            // A job enqueues the next one before it ends, so the chain is over; a moment's wait tells a last job's
            // transaction from the next job's.
            if (sawJobs) delay(SETTLE_MILLIS)
            when (statusOf(batchUuid)) {
                BatchEntity.STATUS_REVIEW, BatchEntity.STATUS_DONE -> {
                    BatchNotifications.post(context, BatchNotifications.finished(context, sizes.sum()))
                    return
                }
                // A cancelled batch, or one a library rebuild took away: nothing is left to tell.
                null, BatchEntity.STATUS_CANCELLED -> {
                    BatchNotifications.cancel(context)
                    return
                }
                // Nothing to watch and nothing more to come: the batch waits for its network or its battery, and its
                // notification stays as the last progress left it.
                else -> if (!sawJobs) return
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // WorkManager or the library can be gone under a batch (a test's, a rebuild's): the Fix tab stays right.
        Log.w(TAG, "The batch notification stopped following the batch", e)
        BatchNotifications.cancel(context)
    }

    /**
     * Shows the batch's progress until every job of it has finished, and says whether any job showed: one waits for
     * the first job for a while, since a batch whose jobs WorkManager no longer holds never shows one.
     */
    private suspend fun watch(batchUuid: String, sizes: List<Int>, meter: EtaMeter): Boolean {
        val jobs = workManager().getWorkInfosByTagFlow(batchUuid)
        val first = withTimeoutOrNull(WAIT_FOR_FIRST_JOB_MILLIS) { jobs.first { it.isNotEmpty() } } ?: return false
        show(first, sizes, meter)
        jobs.takeWhile { infos -> infos.any { !it.state.isFinished } }.collect { show(it, sizes, meter) }
        return true
    }

    /** Posts the batch's progress as [infos] and the meter over its files give it. */
    private fun show(infos: List<WorkInfo>, sizes: List<Int>, meter: EtaMeter) {
        val finished = infos.count { it.state.isFinished }
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { data ->
            JobProgress(data.getInt(BatchData.FILES_DONE, 0), data.getInt(BatchData.FILES_TOTAL, 0))
        }
        val progress = batchProgress(sizes, finished, running)
        val now = clock()
        meter.record(now, progress.done)
        val eta = etaOf(meter.etaMillis(now, progress.done, progress.total))
        BatchNotifications.post(context, BatchNotifications.progress(context, progress, eta))
    }

    /** The sizes of the batch's album groups in their order, or null when the library cannot give them. */
    private suspend fun groupSizes(batchUuid: String): List<Int>? = try {
        val files = RoomBatchState(LibraryAccess.app(context)).files(batchUuid)
        groupByAlbum(files).map { it.files.size }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "The album groups of the batch could not be read", e)
        null
    }

    /** The batch's status, or null when the library no longer holds the batch. */
    private suspend fun statusOf(batchUuid: String): String? = try {
        RoomBatchState(LibraryAccess.app(context)).batch(batchUuid)?.status
    } catch (e: Exception) {
        Log.w(TAG, "The batch's state could not be read", e)
        null
    }

    /** The newest batch that is processing, if any: the one a process death left running (TI §8). */
    private fun lastProcessing(): String? = try {
        LibraryAccess.app(context).use { database ->
            runBlocking { database.batches().inStatus(BatchEntity.STATUS_PROCESSING) }
        }.lastOrNull()?.uuid
    } catch (e: Exception) {
        Log.w(TAG, "The batches could not be read at the start", e)
        null
    }

    companion object {

        /** How long the first job of a batch may take to show: past it, the batch's jobs are gone already. */
        private const val WAIT_FOR_FIRST_JOB_MILLIS = 10_000L

        /** Between a job's last transaction and the next job's, which a worker enqueues before it ends. */
        private const val SETTLE_MILLIS = 500L

        /** How many times the batch's state is read again once its jobs have all ended. */
        private const val ROUNDS = 3

        private const val TAG = "BatchNotifier"

        // It holds the application context only, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BatchNotifier? = null

        /** The app's notifier: one per process, since one notification follows one batch. */
        fun of(context: Context): BatchNotifier = instance ?: synchronized(this) {
            instance ?: BatchNotifier(context.applicationContext).also { instance = it }
        }
    }
}
