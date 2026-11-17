package io.github.lorddetson.piplayer.identification.entry

import android.content.Context
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.library.db.LibraryAccess
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
 * The launcher the app uses (TI §1, §8): writes the batch's `batch` row through [library] and gives back its UUID. The
 * batch stays `queued` until the queue of M4a step 2 (PIM-16) takes it: that step writes the batch's files into
 * `batch_file` and enqueues phase (a) for them, and until it lands the Fix tab lists the batch as it is.
 */
class RoomBatchLauncher(
    context: Context,
    private val library: LibraryAccess = LibraryAccess.app(context),
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : BatchLauncher {

    override suspend fun start(source: String, fileIds: List<Long>): String {
        val batch = queuedBatch(source, BatchPicks.files(fileIds), uuid(), clock())
        withContext(Dispatchers.IO) { library.use { database -> runBlocking { database.batches().save(batch) } } }
        return batch.uuid
    }
}
