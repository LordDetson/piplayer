package io.github.lorddetson.piplayer.covers

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A copy of a chosen image in the covers folder (docs/library.md §13.2). */
data class CoverCopy(val fileName: String, val width: Int, val height: Int)

/**
 * The user's own covers (docs/library.md §13.2): references in `user.db`, copies in [dir]. Read once, then kept in memory;
 * every change raises [version]. Changes do not raise the user database's change counter: nothing of them is mirrored.
 */
class OwnCovers(private val database: UserDatabase, val dir: File, private val clock: () -> Long = System::currentTimeMillis) :
    OwnCoverIndex {

    private class Snapshot(val tracks: Map<Long, String>, val albums: Map<String, String>)

    @Volatile
    private var snapshot: Snapshot? = null
    private val mutex = Mutex()
    private val dao get() = database.covers()

    private val mutableVersion = MutableStateFlow(0L)

    /** Raised by every change, so that views load their covers again. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    /** Called after each change, for the loader to forget what it holds. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Reads the references, once, and deletes copies that none names. */
    suspend fun load() {
        if (snapshot == null) mutex.withLock { loaded() }
    }

    /** The covers, read if they are not yet, keeping the copy [keep] that a change is about to name; holds the lock. */
    private suspend fun loaded(keep: String? = null): Snapshot = snapshot ?: run {
        // A reference whose copy is missing counts as no own cover (§13.2): after a restore, user.db came back and the
        // no-backup files did not. Such references go, so that no menu, artist or session takes them for covers.
        val (tracks, lostTracks) = dao.trackCovers().partition { File(dir, it.fileName).isFile }
        val (albums, lostAlbums) = dao.albumCovers().partition { File(dir, it.fileName).isFile }
        if (lostTracks.isNotEmpty() || lostAlbums.isNotEmpty()) {
            database.withWriteTransaction {
                lostTracks.forEach { dao.removeTrackCover(it.identityId) }
                lostAlbums.forEach { dao.removeAlbumCover(it.albumKey) }
            }
        }
        val names = tracks.map { it.fileName }.toSet() + albums.map { it.fileName } + listOfNotNull(keep)
        dir.listFiles().orEmpty().filter { it.isFile && it.name !in names }.forEach(File::delete)
        Snapshot(tracks.associate { it.identityId to it.fileName }, albums.associate { it.albumKey to it.fileName })
            .also { snapshot = it }
    }

    override fun track(identityId: Long): String? = snapshot?.tracks?.get(identityId)

    override fun album(albumKey: String): String? = snapshot?.albums?.get(albumKey)

    suspend fun setTrack(identityId: Long, copy: CoverCopy) = change(keep = copy.fileName) { current ->
        val old = dao.trackCover(identityId)?.fileName
        dao.putTrackCover(identityId, copy.fileName, copy.width, copy.height, clock())
        delete(old, keep = copy.fileName)
        Snapshot(current.tracks + (identityId to copy.fileName), current.albums)
    }

    suspend fun setAlbum(albumKey: String, copy: CoverCopy) = change(keep = copy.fileName) { current ->
        val old = dao.albumCover(albumKey)?.fileName
        dao.putAlbumCover(albumKey, copy.fileName, copy.width, copy.height, clock())
        delete(old, keep = copy.fileName)
        Snapshot(current.tracks, current.albums + (albumKey to copy.fileName))
    }

    suspend fun removeTrack(identityId: Long) = change { current ->
        val old = dao.trackCover(identityId)?.fileName ?: return@change current
        dao.removeTrackCover(identityId)
        delete(old)
        Snapshot(current.tracks - identityId, current.albums)
    }

    suspend fun removeAlbum(albumKey: String) = change { current ->
        val old = dao.albumCover(albumKey)?.fileName ?: return@change current
        dao.removeAlbumCover(albumKey)
        delete(old)
        Snapshot(current.tracks, current.albums - albumKey)
    }

    /** Forgets every own cover and deletes the copies; tests start so. */
    suspend fun clear() = change { _ ->
        database.withWriteTransaction {
            usePrepared("DELETE FROM track_cover") { it.step() }
            usePrepared("DELETE FROM album_cover") { it.step() }
        }
        dir.listFiles().orEmpty().forEach(File::delete)
        Snapshot(emptyMap(), emptyMap())
    }

    /** The identities that hold an own cover, which the attach pass never deletes (docs/library.md §8.1). */
    suspend fun identitiesWithCovers(): Set<Long> = dao.identitiesWithCovers().toSet()

    /** A copy no longer named, unless it is the new one: choosing the same copy again keeps it. */
    private fun delete(name: String?, keep: String? = null) {
        if (name != null && name != keep) File(dir, name).delete()
    }

    /** Applies [block] to the covers under the lock; [keep] is the new copy it names, which the first read must not delete. */
    private suspend fun change(keep: String? = null, block: suspend (Snapshot) -> Snapshot) {
        mutex.withLock {
            val current = loaded(keep)
            val next = block(current)
            if (next === current) return
            snapshot = next
            // The loader forgets first: a view that hears the new version at once must not get the old cover from memory.
            onChange?.invoke()
            mutableVersion.value++
        }
    }
}
