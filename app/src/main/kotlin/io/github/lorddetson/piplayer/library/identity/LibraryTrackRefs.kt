package io.github.lorddetson.piplayer.library.identity

import io.github.lorddetson.piplayer.library.db.FoundFileRow
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking

/**
 * Track references on the two databases (docs/playback.md §4.8): what the saved queue stores for a song, and the song a
 * saved reference names now. Each call blocks its thread for a few quick reads; callers run off the main thread.
 */
class LibraryTrackRefs(private val access: Access, private val user: UserDatabase) {

    /** How the library database is read: the app goes through its lock, so that a rebuild cannot close it meanwhile. */
    interface Access {
        fun <T> read(block: (LibraryDatabase) -> T): T
    }

    /** On a database of a test's own. */
    constructor(library: LibraryDatabase, user: UserDatabase) : this(
        object : Access {
            override fun <T> read(block: (LibraryDatabase) -> T): T = block(library)
        },
        user,
    )

    fun libraryId(): String = access.read { runBlocking { it.library().libraryId() } }

    /** The reference of the file [fileId], or null when the library has no such file. */
    fun refOf(fileId: Long): TrackRef? {
        val file = access.read { runBlocking { it.library().foundFile(fileId) } } ?: return null
        val identity = file.contentKey?.let { runBlocking { user.identities().idOfKey(Hex.bytes(it)) } }
        return TrackRef(fileId, identity, file.contentKey, file.place(), file.durationMs)
    }

    /** The file ID [ref] names now; [savedLibraryId] is the library ID it was saved under. */
    fun resolve(ref: TrackRef, savedLibraryId: String?): Long? =
        TrackRefs.resolve(ref, sameLibrary = savedLibraryId == libraryId(), lookup)

    /** The file of a media index ID: a saved queue of schema 2 named songs so. */
    fun fileOfMediaStoreId(mediaStoreId: Long): Long? =
        access.read { runBlocking { it.library().fileIdOfMediaStoreId(mediaStoreId) } }

    private val lookup = object : TrackLookup {
        override fun file(fileId: Long) = access.read { runBlocking { it.library().foundFile(fileId)?.found() } }

        override fun identityKey(identityId: Long) =
            runBlocking { user.identities().identity(identityId)?.let { Hex.text(it.contentKey) } }

        override fun filesWithKey(contentKey: String) =
            access.read { runBlocking { it.library().foundFilesWithKey(contentKey).map { row -> row.found() } } }

        override fun filesAt(place: Place) = access.read {
            runBlocking { it.library().foundFilesAt(place.volume, place.relativePath, place.displayName).map { row -> row.found() } }
        }
    }

    private fun FoundFileRow.place() = Place(volume, relativePath, displayName)

    private fun FoundFileRow.found() = FoundFile(fileId, place(), contentKey, durationMs)
}
