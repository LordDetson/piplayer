package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction
import java.text.Collator
import kotlinx.coroutines.flow.Flow

/** A new entry: its song's identity when it has one, and its hints (docs/library.md §16.2). */
data class NewEntry(
    val identityId: Long?,
    val volume: String?,
    val relativePath: String?,
    val displayName: String?,
    val durationMs: Long?,
    val contentKey: ByteArray? = null,
    val recordingMbid: ByteArray? = null,
    val title: String? = null,
    val artist: String? = null,
)

/** The playlists by name, as the Playlists tab and the controller tree list them (the locale's collator, docs/library.md §16.3). */
fun List<PlaylistSummary>.byName(): List<PlaylistSummary> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return sortedWith(compareBy<PlaylistSummary, String>(collator) { it.name }.thenBy { it.id })
}

/**
 * Playlists of the user database (docs/library.md §16.1): every change keeps the positions of a playlist from 0 without
 * gaps in one transaction. Nothing of a playlist is mirrored, so no change raises the change counter.
 */
class Playlists(private val database: UserDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val dao get() = database.playlists()

    fun playlistsFlow(): Flow<List<PlaylistSummary>> = dao.playlistsFlow()

    suspend fun playlists(): List<PlaylistSummary> = dao.playlists()

    fun playlistFlow(id: Long): Flow<PlaylistSummary?> = dao.playlistFlow(id)

    suspend fun playlist(id: Long): PlaylistSummary? = dao.playlist(id)

    fun entryRowsFlow(playlistId: Long): Flow<List<PlaylistEntryRow>> = dao.entryRowsFlow(playlistId)

    suspend fun entryRows(playlistId: Long): List<PlaylistEntryRow> = dao.entryRows(playlistId)

    suspend fun entries(playlistId: Long): List<PlaylistEntryEntity> = dao.entries(playlistId)

    /** A new empty playlist named [name], trimmed; a blank name is refused with an [IllegalArgumentException]. */
    suspend fun create(name: String): Long {
        val clean = requireNotNull(cleanName(name)) { "A playlist needs a name" }
        val now = clock()
        return dao.addPlaylist(PlaylistEntity(name = clean, createdAt = now, updatedAt = now))
    }

    /** Renames the playlist [id]; false when there is none. A blank name is refused as by [create]. */
    suspend fun rename(id: Long, name: String): Boolean {
        val clean = requireNotNull(cleanName(name)) { "A playlist needs a name" }
        return dao.rename(id, clean, clock()) > 0
    }

    /** Deletes the playlist [id] with its entries; false when there is none. */
    suspend fun delete(id: Long): Boolean = database.withWriteTransaction {
        dao.deleteEntriesOf(id)
        dao.deletePlaylist(id) > 0
    }

    /**
     * Puts [entries] at the end of the playlist [playlistId], in order; how many. Each identity is checked in the write
     * transaction ([current]): an entry whose identity is gone takes the one of its key, else none, for linking (§16.2).
     */
    suspend fun add(playlistId: Long, entries: List<NewEntry>): Int {
        if (entries.isEmpty()) return 0
        return database.withWriteTransaction {
            requireNotNull(dao.playlist(playlistId)) { "No playlist $playlistId" }
            val first = dao.count(playlistId)
            val identities = database.identities()
            dao.addEntries(
                entries.mapIndexed { i, entry ->
                    PlaylistEntryEntity(
                        playlistId = playlistId,
                        position = first + i,
                        identityId = entry.identityId?.let { identities.current(it, entry.contentKey) },
                        hintVolumeName = entry.volume,
                        hintRelativePath = entry.relativePath,
                        hintDisplayName = entry.displayName,
                        hintDurationMs = entry.durationMs,
                        hintContentKey = entry.contentKey,
                        hintRecordingMbid = entry.recordingMbid,
                        hintTitle = entry.title,
                        hintArtist = entry.artist,
                    )
                },
            )
            dao.touch(playlistId, clock())
            entries.size
        }
    }

    /** Removes the entry [entryId] and closes its gap; the entry as it was, for [restore], or null when there is none. */
    suspend fun remove(entryId: Long): PlaylistEntryEntity? = database.withWriteTransaction {
        val entry = dao.entry(entryId) ?: return@withWriteTransaction null
        dao.deleteEntry(entryId)
        dao.shift(entry.playlistId, entry.position + 1, Int.MAX_VALUE, -1)
        dao.touch(entry.playlistId, clock())
        entry
    }

    /**
     * Puts a removed [entry] back at its position, or at the end when the playlist is shorter now. Its identity may have
     * gone meanwhile, and is checked as by [add] with the entry's key hint.
     */
    suspend fun restore(entry: PlaylistEntryEntity) {
        database.withWriteTransaction {
            if (dao.playlist(entry.playlistId) == null || dao.entry(entry.id) != null) return@withWriteTransaction
            val position = entry.position.coerceIn(0, dao.count(entry.playlistId))
            val identity = entry.identityId?.let { database.identities().current(it, entry.hintContentKey) }
            dao.shift(entry.playlistId, position, Int.MAX_VALUE, 1)
            dao.addEntries(listOf(entry.copy(position = position, identityId = identity)))
            dao.touch(entry.playlistId, clock())
        }
    }

    /** Moves the entry [entryId] to position [to] (kept within the playlist); false when nothing moved. */
    suspend fun move(entryId: Long, to: Int): Boolean = database.withWriteTransaction { moveTo(entryId) { to } }

    /**
     * Moves the entry [entryId] [by] places from where it stands now (kept within the playlist), so that "Move up" asked
     * twice before the page shows the first move goes up twice; false when nothing moved.
     */
    suspend fun moveBy(entryId: Long, by: Int): Boolean = database.withWriteTransaction { moveTo(entryId) { from -> from + by } }

    private suspend fun moveTo(entryId: Long, target: (Int) -> Int): Boolean {
        val entry = dao.entry(entryId) ?: return false
        val from = entry.position
        val to = target(from).coerceIn(0, dao.count(entry.playlistId) - 1)
        if (to == from) return false
        if (to > from) dao.shift(entry.playlistId, from + 1, to + 1, -1) else dao.shift(entry.playlistId, to, from, 1)
        dao.setPosition(entryId, to)
        dao.touch(entry.playlistId, clock())
        return true
    }

    /** Forgets every playlist; tests start so. */
    suspend fun clear() = database.withWriteTransaction {
        dao.deleteAllEntries()
        dao.deleteAllPlaylists()
    }

    companion object {
        /** [name] as a playlist keeps it, or null when it is blank. */
        fun cleanName(name: String): String? = name.trim().ifEmpty { null }
    }
}
