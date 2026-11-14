package io.github.lorddetson.piplayer.library.mirror

import android.util.Log
import androidx.room3.PooledConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.JsonRows
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.MirrorDao
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.PlayStats
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The own album covers as the mirror moves them (docs/library.md §15.3); `OwnCovers` in the app. */
interface AlbumCoverMoves {
    /** The album keys with an own cover, each with the time it was set. */
    suspend fun albumCoverTimes(): Map<String, Long>

    /** Moves the covers of the old keys to the new ones; of covers meeting on one key the one set last wins. */
    suspend fun moveAlbumCovers(moves: Map<String, String>)
}

/**
 * Mirrors favorites, favorite albums and play counts of the user database into the library database, and moves album
 * references when an album's key changes (docs/library.md §15.3, §15.4). One run at a time, on a background thread;
 * it reads and writes the library database while no sync writes.
 */
class UserMirror(
    private val library: LibraryDatabase,
    private val user: UserDatabase,
    private val sync: LibrarySync,
    private val covers: AlbumCoverMoves? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether the library knows its files; before its first sync there is nothing to match. */
    private val ready: () -> Boolean = { true },
) {
    /** What one content key mirrors. */
    private data class Values(val favorite: Boolean, val playCount: Long, val lastPlayedAt: Long?)

    private val mutableVersion = MutableStateFlow(0L)

    /** Raised after every run that wrote the mirror, so that lists that read it read again. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val idle = MutableStateFlow(true)
    private val lock = Any()
    private var pendingFull = false
    private val pendingFiles = LinkedHashSet<Long>()

    /** The values of the last full run, by content key, for the partial runs. */
    @Volatile
    private var values: Map<String, Values>? = null

    /** Whether this mirror has folded old play events yet: once per process (docs/library.md §15.5). */
    private var folded = false

    private val mirrorDao: MirrorDao get() = library.mirror()

    init {
        scope.launch {
            for (request in requests) {
                val (full, files) = synchronized(lock) {
                    (pendingFull to pendingFiles.toList()).also {
                        pendingFull = false
                        pendingFiles.clear()
                    }
                }
                try {
                    // A full run covers the files too.
                    if (full) mirror() else if (files.isNotEmpty()) mirrorFiles(files)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The mirror of user data failed", e)
                }
                synchronized(lock) { if (!pendingFull && pendingFiles.isEmpty()) idle.value = true }
            }
        }
    }

    /** Asks for a full run in the background; requests during a run collapse into one more run. */
    fun request() {
        synchronized(lock) {
            pendingFull = true
            idle.value = false
        }
        requests.trySend(Unit)
    }

    /** Asks for a partial run for the files [fileIds], which the tag pass has just read. */
    fun requestFiles(fileIds: List<Long>) {
        if (fileIds.isEmpty()) return
        synchronized(lock) {
            pendingFiles.addAll(fileIds)
            idle.value = false
        }
        requests.trySend(Unit)
    }

    /** One full run now, after any that runs. */
    suspend fun mirror() = running.withLock { fullRun() }

    /** One partial run for [fileIds] now, from the values of the last full run; a full run when there was none. */
    suspend fun mirrorFiles(fileIds: List<Long>) = running.withLock {
        if (values == null) fullRun() else filesRun(fileIds)
    }

    /** Waits until no run runs and none is asked for. */
    suspend fun awaitIdle() {
        idle.first { it }
        running.withLock { }
    }

    fun close() {
        requests.close()
        scope.cancel()
    }

    /** [close], then waits until a run under way has ended, so that the databases may be closed. */
    suspend fun closeAndJoin() {
        close()
        scope.coroutineContext[Job]?.join()
        running.withLock { }
    }

    private suspend fun fullRun() {
        if (!ready()) return
        if (!folded) {
            PlayStats(user).fold(clock())
            folded = true
        }
        sync.exclusive {
            relink()
            // The counter first: the values read after it hold at least what it counts.
            val counter = user.user().changeCounter()
            val current = HashMap<String, Values>()
            for (row in user.favorites().favoriteKeys()) current[Hex.text(row.contentKey)] = Values(true, 0, null)
            for (row in user.plays().playedKeys()) {
                val key = Hex.text(row.contentKey)
                current[key] = (current[key] ?: Values(false, 0, null)).copy(playCount = row.playCount, lastPlayedAt = row.lastPlayedAt)
            }
            val albums = user.favorites().favoriteAlbums().map { it.albumKey }
            library.withWriteTransaction {
                execute("DELETE FROM track_mirror")
                execute("DELETE FROM album_mirror")
                insertTracks(current.entries.toList(), fileIds = null)
                for (chunk in albums.chunked(ROWS_PER_STATEMENT)) {
                    val json = JsonRows().apply { chunk.forEach { key -> row { text(key) } } }.toString()
                    execute("INSERT OR IGNORE INTO album_mirror (album_key) SELECT value->>0 FROM json_each(?)", json)
                }
                mirrorDao.setMirroredCounter(counter)
            }
            values = current
        }
        mutableVersion.value++
    }

    private suspend fun filesRun(fileIds: List<Long>) {
        val current = values ?: return
        // Without user data the full run left no rows, and a batch of the tag pass needs no write of its own.
        if (current.isEmpty() || !ready()) return
        sync.exclusive {
            library.withWriteTransaction {
                for (chunk in fileIds.distinct().chunked(MirrorDao.CHUNK)) {
                    val ids = chunk.joinToString(",", "[", "]")
                    execute("DELETE FROM track_mirror WHERE file_id IN (SELECT value FROM json_each(?))", ids)
                    val keys = mirrorDao.keysOf(chunk).mapNotNull { it.contentKey }.distinct()
                    insertTracks(keys.mapNotNull { key -> current[key]?.let { java.util.AbstractMap.SimpleEntry(key, it) } }, ids)
                }
            }
        }
        mutableVersion.value++
    }

    /** Writes a row for every file of each key, or only for the files of the JSON array [fileIds]. */
    private suspend fun PooledConnection.insertTracks(entries: List<Map.Entry<String, Values>>, fileIds: String?) {
        val only = if (fileIds == null) "" else " WHERE f.id IN (SELECT value FROM json_each(?))"
        val sql = "INSERT OR REPLACE INTO track_mirror (file_id, favorite, play_count, last_played_at) " +
            "SELECT f.id, j.value->>1, j.value->>2, j.value->>3 FROM json_each(?) j " +
            "JOIN audio_file f ON f.content_key = j.value->>0$only"
        for (chunk in entries.chunked(ROWS_PER_STATEMENT)) {
            val json = JsonRows().apply {
                for ((key, value) in chunk) {
                    row {
                        text(key)
                        flag(value.favorite)
                        long(value.playCount)
                        long(value.lastPlayedAt)
                    }
                }
            }.toString()
            if (fileIds == null) execute(sql, json) else execute(sql, json, fileIds)
        }
    }

    /**
     * Moves the references of albums whose key names nothing any more and records the members of the referenced albums
     * (docs/library.md §15.3). Writes only the user database and the own covers.
     */
    private suspend fun relink() {
        val favorites = user.favorites()
        val favoriteAlbums = favorites.favoriteAlbums().associateBy { it.albumKey }
        val coverTimes = covers?.albumCoverTimes().orEmpty()
        val referenced = favoriteAlbums.keys + coverTimes.keys
        val members = favorites.members().groupBy({ it.albumKey }, { it.identityId }).mapValues { it.value.toSet() }
        val present = referenced.toList().chunked(MirrorDao.CHUNK).flatMap { mirrorDao.presentAlbums(it) }.toSet()
        val gone = referenced - present

        val moves = if (gone.isEmpty()) emptyMap() else AlbumRelink.moves(referenced, present, members, albumsOf(members, gone))
        if (moves.isNotEmpty()) {
            user.withWriteTransaction {
                var favoritesMoved = false
                for ((old, new) in moves) {
                    favoriteAlbums[old]?.let { favorite ->
                        val there = favorites.favoriteAlbum(new)
                        favorites.removeAlbum(old)
                        favorites.putAlbum(new, Favorites.releaseMbidOf(new), minOf(favorite.addedAt, there?.addedAt ?: Long.MAX_VALUE))
                        favoritesMoved = true
                    }
                    for (identity in members[old].orEmpty()) favorites.addMember(new, identity)
                    favorites.removeMembers(old)
                }
                if (favoritesMoved) user.user().countChange()
            }
            covers?.moveAlbumCovers(moves)
        }

        // Members of the albums referenced now, while their keys name them; only added (§15.3).
        val nowReferenced = (referenced - moves.keys) + moves.values
        val named = nowReferenced.filter { it in present || it in moves.values }
        val files = named.chunked(MirrorDao.CHUNK).flatMap { mirrorDao.albumFiles(it) }
        val known = favorites.members().mapTo(HashSet()) { it.albumKey to it.identityId }
        val identities = HashMap<String, Long?>()
        val added = files.mapNotNull { file ->
            val identity = identities.getOrPut(file.contentKey) { user.identities().idOfKey(Hex.bytes(file.contentKey)) }
            identity?.let { file.albumKey to it }?.takeIf { it !in known }
        }.distinct()
        val unreferenced = known.map { it.first }.filter { it !in nowReferenced }.toSet()
        if (added.isEmpty() && unreferenced.isEmpty() && known.isEmpty()) return
        user.withWriteTransaction {
            for ((album, identity) in added) favorites.addMember(album, identity)
            for (album in unreferenced) favorites.removeMembers(album)
            favorites.removeLostMembers()
        }
    }

    /** The album keys of the files that hold the keys of the members of [gone] albums now, by identity. */
    private suspend fun albumsOf(members: Map<String, Set<Long>>, gone: Set<String>): Map<Long, Set<String>> {
        val ids = gone.flatMap { members[it].orEmpty() }.toSet()
        val keyOf = ids.mapNotNull { id -> user.identities().identity(id)?.let { id to Hex.text(it.contentKey) } }.toMap()
        val albumsByKey = keyOf.values.distinct().chunked(MirrorDao.CHUNK)
            .flatMap { mirrorDao.albumsOfKeys(it) }
            .mapNotNull { row -> row.albumKey?.let { row.contentKey to it } }
            .groupBy({ it.first }, { it.second })
        return keyOf.mapValues { (_, key) -> albumsByKey[key].orEmpty().toSet() }.filterValues { it.isNotEmpty() }
    }

    private suspend fun PooledConnection.execute(sql: String, vararg texts: String) {
        usePrepared(sql) { statement ->
            texts.forEachIndexed { i, text -> statement.bindText(i + 1, text) }
            statement.step()
        }
    }

    private companion object {
        const val TAG = "UserMirror"
        const val ROWS_PER_STATEMENT = 2_000
    }
}
