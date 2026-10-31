package io.github.lorddetson.piplayer.library.sync

/** What the library database last reached on a volume (docs/library.md §1.3). */
data class StoredVolume(val volume: String, val version: String, val generation: Long)

/** A file the library database holds for a volume, with what a sync matches it by. */
data class StoredFile(val fileId: Long, val mediaStoreId: Long, val relativePath: String, val displayName: String)

/** How a volume is synced. */
sealed interface VolumeMode {
    /** Every row is read and matched by location. */
    data object Full : VolumeMode

    /** Only rows changed after [since] are read; they are matched by the index's ID. */
    data class Incremental(val since: Long) : VolumeMode
}

/** A stored file that takes the values of [row]. */
data class FileUpdate(val fileId: Long, val row: IndexRow)

/** What one volume's sync changes in the library database. */
data class FileChanges(val removed: List<Long>, val updated: List<FileUpdate>, val added: List<IndexRow>) {
    val isEmpty: Boolean get() = removed.isEmpty() && updated.isEmpty() && added.isEmpty()
}

/** The decisions of a sync, apart from reading the index and writing the database (docs/library.md §1.3). */
object SyncPlanner {

    fun mode(stored: StoredVolume?, version: String, generation: Long): VolumeMode = when {
        stored == null || stored.version != version || generation < stored.generation -> VolumeMode.Full
        else -> VolumeMode.Incremental(stored.generation)
    }

    /** The stored volumes that are no longer attached. */
    fun detached(stored: Collection<String>, attached: Set<String>): Set<String> = stored.toSet() - attached

    /** A full sync of a volume: [rows] are all its audio rows. */
    fun full(stored: List<StoredFile>, rows: List<IndexRow>): FileChanges {
        val byLocation = stored.associateBy { it.relativePath to it.displayName }
        val updated = mutableListOf<FileUpdate>()
        val added = mutableListOf<IndexRow>()
        val kept = HashSet<Long>()
        for (row in lastPerId(rows)) {
            val file = byLocation[row.relativePath to row.displayName]
            if (file != null && kept.add(file.fileId)) updated += FileUpdate(file.fileId, row) else added += row
        }
        return FileChanges(removed = stored.map { it.fileId }.filterNot { it in kept }, updated = updated, added = added)
    }

    /** An incremental sync: [changed] are the rows changed since the last sync, [ids] the IDs of all audio rows now. */
    fun incremental(stored: List<StoredFile>, changed: List<IndexRow>, ids: Set<Long>): FileChanges {
        val byId = stored.associateBy { it.mediaStoreId }
        val updated = mutableListOf<FileUpdate>()
        val added = mutableListOf<IndexRow>()
        // The IDs are read last, so a row they no longer have is gone, even if it changed first.
        for (row in lastPerId(changed).filter { it.mediaStoreId in ids }) {
            val file = byId[row.mediaStoreId]
            if (file != null) updated += FileUpdate(file.fileId, row) else added += row
        }
        return FileChanges(
            removed = stored.filterNot { it.mediaStoreId in ids }.map { it.fileId },
            updated = updated,
            added = added,
        )
    }

    /** A scan between two cursor windows can shift rows, so the same row may come twice; the later read wins. */
    private fun lastPerId(rows: List<IndexRow>): Collection<IndexRow> =
        rows.associateBy { it.mediaStoreId }.values
}
