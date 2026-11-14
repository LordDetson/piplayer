package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.library.db.LibraryDao
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.OutsideFileEntity
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The counts of failed reads in the library database (docs/library.md §4.4): library files in `audio_file`, other
 * files in `outside_file`. Each count reads and writes through [access], so that a rebuild of the library, which closes
 * the database once the reads under way have ended, never closes it under one.
 */
class LibraryUnsafeCounts(private val access: LibraryTrackRefs.Access) : UnsafeCounts {

    /** On a database of a test's own. */
    constructor(database: LibraryDatabase) : this(
        object : LibraryTrackRefs.Access {
            override fun <T> read(block: (LibraryDatabase) -> T): T = block(database)
        },
    )

    /** [block] on the database the library has now, which may be another one after a rebuild. */
    private suspend fun <T> withDao(block: suspend (LibraryDao) -> T): T = withContext(Dispatchers.IO) {
        access.read { database -> runBlocking { block(database.library()) } }
    }

    override suspend fun count(target: TagTarget): Int = withDao { dao -> count(dao, target) }

    private suspend fun count(dao: LibraryDao, target: TagTarget): Int = when (target) {
        is TagTarget.LibraryFile -> dao.unsafeCount(target.fileId) ?: 0
        is TagTarget.Outside -> dao.outsideFile(target.uri)
            ?.takeIf { it.size == target.size && it.mtime == target.mtimeMs }
            ?.unsafeCount ?: 0
    }

    override suspend fun raise(target: TagTarget) = withDao { dao ->
        val raised = count(dao, target) + 1
        when (target) {
            is TagTarget.LibraryFile -> dao.setUnsafeCount(target.fileId, raised)
            is TagTarget.Outside -> dao.saveOutsideFile(OutsideFileEntity(target.uri, target.size, target.mtimeMs, raised))
        }
    }

    override suspend fun reset(target: TagTarget) = withDao { dao ->
        when (target) {
            is TagTarget.LibraryFile -> dao.setUnsafeCount(target.fileId, 0)
            is TagTarget.Outside -> dao.forgetOutsideFile(target.uri)
        }
    }
}
