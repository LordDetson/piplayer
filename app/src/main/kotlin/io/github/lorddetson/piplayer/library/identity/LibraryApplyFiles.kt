package io.github.lorddetson.piplayer.library.identity

import io.github.lorddetson.piplayer.library.db.LibraryAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The library's files as Apply gives them an identity (TI §10.2 "An identity at Apply"), read through [library] so that
 * a rebuild never closes the database under the read. The length counts as settled: the stored or else the index's
 * length stands in for a walk the tag pass has not made yet, since step 2 of re-attachment needs one, unless the target
 * carries stage 0's exact length, which Apply takes instead.
 */
class LibraryApplyFiles(
    private val library: LibraryAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApplyFiles {

    override suspend fun file(fileId: Long, contentKey: String): ApplyFile? = withContext(ioDispatcher) {
        // The block runs under the library's lock, which is held by its thread: the read blocks that thread.
        val (row, shared) = library.use { database ->
            runBlocking {
                val row = database.library().applyFile(fileId) ?: return@runBlocking null
                // Whether another file holds the key the library still stores for this one.
                val shared = row.contentKey.isEmpty() || database.library().foundFilesWithKey(row.contentKey).any { it.fileId != fileId }
                row to shared
            }
        } ?: return@withContext null
        val file = KeyedFile(
            fileId = row.fileId,
            contentKey = contentKey,
            place = Place(row.volume, row.relativePath, row.displayName),
            size = row.size,
            durationMs = row.durationMs,
            settled = true,
            recordingMbid = null,
            format = row.format,
            tagsRead = false,
        )
        ApplyFile(file, heldKey = row.contentKey.takeIf { !shared && it != contentKey })
    }
}
