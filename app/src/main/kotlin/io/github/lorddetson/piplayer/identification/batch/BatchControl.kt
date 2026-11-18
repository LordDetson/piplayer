package io.github.lorddetson.piplayer.identification.batch

import android.content.Context
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import kotlinx.coroutines.runBlocking

/**
 * What the user may do to a running batch (TI §8, PIM-26): pause it, resume it at the album group it stopped on, or
 * stop it for good. A pause and a stop cancel the batch's queued jobs, so nothing is left half done; the phases are
 * idempotent, so a batch resumed later reads its group again and continues (TI §8, [BatchResume]).
 *
 * The three effects are functions, so the whole decision is testable without WorkManager or a database.
 */
class BatchControl(
    private val batch: suspend (uuid: String) -> BatchEntity?,
    private val setStatus: suspend (uuid: String, status: String) -> Unit,
    private val cancelJobs: (uuid: String) -> Unit,
    private val enqueueScan: (uuid: String, groupIndex: Int) -> Unit,
) {

    /** Pauses a batch that is queued, processing or waiting for review; false when it cannot be paused. */
    suspend fun pause(uuid: String): Boolean = change(uuid, BatchEntity.STATUS_PAUSED) { cancelJobs(uuid) }

    /** Resumes a paused batch at the group it is on; false when it is not paused. */
    suspend fun resume(uuid: String): Boolean {
        val row = batch(uuid) ?: return false
        if (row.status != BatchEntity.STATUS_PAUSED) return false
        setStatus(uuid, BatchEntity.STATUS_PROCESSING)
        enqueueScan(uuid, row.groupIndex.coerceAtLeast(0))
        return true
    }

    /** Stops a batch for good; false when it is already done or cancelled. */
    suspend fun stop(uuid: String): Boolean = change(uuid, BatchEntity.STATUS_CANCELLED) { cancelJobs(uuid) }

    private suspend fun change(uuid: String, to: String, effect: () -> Unit): Boolean {
        val row = batch(uuid) ?: return false
        if (row.status == BatchEntity.STATUS_DONE || row.status == BatchEntity.STATUS_CANCELLED) return false
        setStatus(uuid, to)
        effect()
        return true
    }

    companion object {

        /** The control of the app's batches: the `batch` table and the WorkManager queue (TI §8). */
        fun of(context: Context, library: LibraryAccess = LibraryAccess.app(context)): BatchControl {
            val queue = BatchQueue(context)
            return BatchControl(
                batch = { uuid -> library.use { database -> runBlocking { database.batches().batch(uuid) } } },
                setStatus = { uuid, status -> library.use { database -> runBlocking { database.batches().setStatus(uuid, status) } } },
                cancelJobs = queue::cancel,
                enqueueScan = queue::enqueueScan,
            )
        }
    }
}