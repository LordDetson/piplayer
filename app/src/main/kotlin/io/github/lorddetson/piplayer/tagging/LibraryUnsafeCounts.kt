package io.github.lorddetson.piplayer.tagging

import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.OutsideFileEntity

/**
 * The counts of failed reads in the library database (docs/library.md §4.4): library files in `audio_file`, other
 * files in `outside_file`.
 */
class LibraryUnsafeCounts(private val database: () -> LibraryDatabase) : UnsafeCounts {

    constructor(database: LibraryDatabase) : this({ database })

    /** Asked for each time: the library's database may be another one by now. */
    private val dao get() = database().library()

    override suspend fun count(target: TagTarget): Int = when (target) {
        is TagTarget.LibraryFile -> dao.unsafeCount(target.fileId) ?: 0
        is TagTarget.Outside -> dao.outsideFile(target.uri)
            ?.takeIf { it.size == target.size && it.mtime == target.mtimeMs }
            ?.unsafeCount ?: 0
    }

    override suspend fun raise(target: TagTarget) {
        val raised = count(target) + 1
        when (target) {
            is TagTarget.LibraryFile -> dao.setUnsafeCount(target.fileId, raised)
            is TagTarget.Outside -> dao.saveOutsideFile(OutsideFileEntity(target.uri, target.size, target.mtimeMs, raised))
        }
    }

    override suspend fun reset(target: TagTarget) {
        when (target) {
            is TagTarget.LibraryFile -> dao.setUnsafeCount(target.fileId, 0)
            is TagTarget.Outside -> dao.forgetOutsideFile(target.uri)
        }
    }
}
