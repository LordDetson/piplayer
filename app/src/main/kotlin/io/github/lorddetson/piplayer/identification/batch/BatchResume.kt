package io.github.lorddetson.piplayer.identification.batch

import android.content.Context
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.BatchEntity
import kotlinx.coroutines.runBlocking

/**
 * The batches a new process picks up again (TI §8, PIM-16): a batch that was queued or processing when its process died
 * has its album group kept in `batch.group_index`, and its jobs are enqueued again from that group. The phases are
 * idempotent, so a group whose phase finished before the death costs one re-read and no wrong result.
 *
 * WorkManager keeps its own queue across a process death, so this matters for the cases it does not cover: a force stop,
 * which cancels the queue, and a rebuild of the library that dropped the queue with the application's data.
 */
object BatchResume {

    /** The batches to enqueue again, each with the group it is on; finished and cancelled ones are left alone. */
    fun resumable(batches: List<BatchEntity>): List<Pair<String, Int>> = batches
        .filter { it.status == BatchEntity.STATUS_QUEUED || it.status == BatchEntity.STATUS_PROCESSING }
        .map { it.uuid to it.groupIndex.coerceAtLeast(0) }

    /** Enqueues phase (a) of every batch [resumable] names; a job already queued is kept by its unique name. */
    suspend fun resume(context: Context, queue: BatchQueue = BatchQueue(context)) {
        val batches = Library.read(context) { database -> runBlocking { database.batches().all() } }
        resumable(batches).forEach { (uuid, group) -> queue.enqueueScan(uuid, group) }
    }
}