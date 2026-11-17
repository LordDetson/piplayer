package io.github.lorddetson.piplayer.identification.entry

import android.content.Context
import io.github.lorddetson.piplayer.identification.batch.BatchQueue
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.BatchFileEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.notifications.BatchNotifier
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * How an entry point starts a batch (TI §1, §8): [start] makes the batch over [fileIds] and gives its UUID back, which
 * the caller keeps for as long as the batch shows. What a batch needs beside its row is not the entry point's business,
 * so an implementation may grow without a screen knowing: a screen starts batches and reads nothing of WorkManager.
 */
fun interface BatchLauncher {

    /**
     * Starts a batch from [source], over [fileIds] in their order, each once, and returns the batch's UUID. Blocks the
     * calling coroutine.
     */
    suspend fun start(source: String, fileIds: List<Long>): String
}

/**
 * The `batch` row of a batch started from [source] over [fileIds] (TI §8): `queued` at [createdAt], with [uuid] as its ID
 * and `write_to_file` left empty for M4b. A batch needs at least one file, and a source of the entry points of TI §1.
 */
fun queuedBatch(source: String, fileIds: List<Long>, uuid: String, createdAt: Long): BatchEntity {
    require(fileIds.isNotEmpty()) { "A batch needs at least one file" }
    require(source == BatchEntity.SOURCE_FOLDER || source == BatchEntity.SOURCE_SELECTION || source == BatchEntity.SOURCE_MISSING_INFO) {
        "Unknown batch source: $source"
    }
    return BatchEntity(uuid = uuid, createdAt = createdAt, source = source, status = BatchEntity.STATUS_QUEUED)
}

/**
 * The launcher the app uses (TI §1, §8): writes the batch's `batch` row and its files through [library], then hands the
 * batch to [queue], which enqueues phase (a) of its first album group. A screen only starts batches; the queue and the
 * workers of [io.github.lorddetson.piplayer.identification.batch] take it from there. The batch's notification follows
 * the batch through [follow], which watches the jobs the queue runs (TI §8, PIM-21).
 */
class RoomBatchLauncher(
    context: Context,
    private val library: LibraryAccess = LibraryAccess.app(context),
    private val queue: BatchQueues = WorkBatchQueues(context.applicationContext),
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val follow: (String) -> Unit = { BatchNotifier.of(context).started(it) },
) : BatchLauncher {

    override suspend fun start(source: String, fileIds: List<Long>): String {
        val files = BatchPicks.files(fileIds)
        val batch = queuedBatch(source, files, uuid(), clock())
        withContext(Dispatchers.IO) {
            library.use { database ->
                runBlocking {
                    database.batches().save(batch)
                    database.batches().addFiles(files.mapIndexed { position, fileId -> BatchFileEntity(batch.uuid, position, fileId) })
                }
            }
        }
        queue.start(batch.uuid)
        // After the queue, so that the notification finds the first job; a device without notifications posts none.
        follow(batch.uuid)
        return batch.uuid
    }
}

/** How a started batch reaches its jobs (TI §8); [WorkBatchQueues] is the app's, tests use their own. */
fun interface BatchQueues {

    /** Enqueues the batch's first job; the batch row and its files are written already. */
    fun start(batchUuid: String)
}

/** The app's queue: phase (a) of the first group, then the chain the workers enqueue (TI §8). */
class WorkBatchQueues(context: Context) : BatchQueues {

    private val context = context.applicationContext

    override fun start(batchUuid: String) = BatchQueue(context).start(batchUuid)
}
