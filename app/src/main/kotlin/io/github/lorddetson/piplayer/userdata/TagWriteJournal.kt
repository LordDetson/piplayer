package io.github.lorddetson.piplayer.userdata

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecord

/** Records the verified working copy and its snapshot together before the original can be opened for writing. */
internal class TagWriteJournal(private val database: UserDatabase) {
    private val dao get() = database.tagWrites()
    private val archive = TagWriteArchive(database)
    private val queue = TagApplyQueue(database)

    suspend fun prepare(backup: TagBackupEntity, change: AppliedChangeEntity): Long = database.withWriteTransaction {
        insert(backup, change)
    }

    suspend fun prepareForApply(backup: TagBackupEntity, change: AppliedChangeEntity, item: TagApplyItem): Long? =
        database.withWriteTransaction {
            require(change.decisionId == item.decisionId && Hex.text(change.expectedContentKey) == item.contentKey &&
                change.volumeName == item.volumeName && change.relativePath == item.relativePath && change.displayName == item.displayName)
            val apply = database.tagApplyQueue()
            if (item.writeId != null || item.result != "queued" || apply.item(item.planId, item.fileId) != item) {
                return@withWriteTransaction null
            }
            val id = insert(backup, change)
            check(apply.attachWrite(item.planId, item.fileId, id) == 1)
            id
        }

    private suspend fun insert(backup: TagBackupEntity, change: AppliedChangeEntity): Long {
        require(change.id == 0L && backup.id == 0L && change.backupId == 0L && change.state == "pending")
        require(change.identityId == backup.identityId && change.volumeName == backup.volumeName &&
            change.relativePath == backup.relativePath && change.displayName == backup.displayName)
        return dao.insertChange(change.copy(backupId = dao.insertBackup(backup)))
    }

    suspend fun entry(id: Long): AppliedChangeEntity? = dao.entry(id) ?: archive.entry(id)
    suspend fun backup(id: Long): TagBackupEntity? = dao.backup(id) ?: archive.backup(id)
    suspend fun interrupted(): List<AppliedChangeEntity> = dao.interrupted()
    suspend fun resumableSingles(identityId: Long? = null): List<AppliedChangeEntity> = dao.resumableSingles(identityId)
    suspend fun resumableForTrack(identityId: Long): List<AppliedChangeEntity> = dao.resumableForTrack(identityId)
    suspend fun abandonPending(id: Long): Boolean = dao.abandonPending(id) == 1
    suspend fun observedWrites(identityIds: Collection<Long>): Map<Long, List<AppliedChangeEntity>> =
        identityIds.distinct().chunked(500).flatMap { dao.observedWrites(it) + archive.forIdentities(it) }
            .distinctBy { it.id }.groupBy { it.identityId }
    suspend fun forTrack(identityId: Long): List<AppliedChangeEntity> =
        (dao.forTrack(identityId) + archive.forIdentities(listOf(identityId))).distinctBy { it.id }.sortedByDescending { it.id }
    suspend fun forBatch(batchUuid: ByteArray): List<AppliedChangeEntity> =
        (dao.forBatch(batchUuid) + archive.forBatch(batchUuid)).distinctBy { it.id }.sortedByDescending { it.id }

    suspend fun pendingLibraryRefresh(): List<Long> = dao.pendingLibraryRefresh()

    suspend fun libraryRefreshed(id: Long) = dao.libraryRefreshed(id)

    suspend fun beginStreaming(id: Long): Boolean = dao.markStreaming(id) == 1
    suspend fun streamed(id: Long): Boolean = dao.markStreamed(id) == 1
    suspend fun verified(
        id: Long, size: Long, mtime: Long, at: Long, undoOf: Long? = null,
        writtenPartHashes: Map<Int, Int> = emptyMap(),
        refreshLibrary: Boolean = false,
    ): Boolean =
        database.withWriteTransaction {
            if (undoOf != null && entry(undoOf)?.let { it.state == "verified" && it.undoneAt == null } != true) {
                return@withWriteTransaction false
            }
            if (dao.markVerified(id, size, mtime, at) != 1) return@withWriteTransaction false
            if (undoOf != null) check(dao.markUndone(undoOf, at) == 1 || archive.markUndone(undoOf, at))
            if (writtenPartHashes.isNotEmpty()) {
                val entry = checkNotNull(dao.entry(id))
                val record = checkNotNull(TagWriteRecord.decode(entry.diff))
                val winners = Decisions(database).acceptWrittenTagParts(entry, writtenPartHashes.keys)
                dao.saveVerifiedReferences(id, record.withVerifiedReferences(winners, entry.decisionId, writtenPartHashes).encode())
            }
            queue.recordVerifiedWrite(id)
            if (refreshLibrary) dao.addLibraryRefresh(TagWriteRefresh(id))
            archive.compactPage()
            true
        }
    suspend fun failed(id: Long): Boolean = dao.markFailed(id) == 1
}

@Dao
interface TagWriteDao {
    @Insert
    suspend fun addLibraryRefresh(refresh: TagWriteRefresh)

    @Query("SELECT change_id FROM tag_write_refresh ORDER BY change_id")
    suspend fun pendingLibraryRefresh(): List<Long>

    @Query("DELETE FROM tag_write_refresh WHERE change_id = :id")
    suspend fun libraryRefreshed(id: Long)

    @Insert
    suspend fun insertBackup(backup: TagBackupEntity): Long

    @Insert
    suspend fun insertChange(change: AppliedChangeEntity): Long

    @Query("SELECT * FROM applied_change WHERE id = :id")
    suspend fun entry(id: Long): AppliedChangeEntity?

    @Query("SELECT * FROM tag_backup WHERE id = :id")
    suspend fun backup(id: Long): TagBackupEntity?

    @Query("SELECT * FROM applied_change WHERE state IN ('pending', 'streaming', 'streamed') ORDER BY id")
    suspend fun interrupted(): List<AppliedChangeEntity>

    @Query(
        "SELECT * FROM applied_change WHERE batch_uuid IS NULL AND " +
            "state IN ('pending', 'streaming', 'streamed', 'failed') " +
            "AND (:identityId IS NULL OR identity_id = :identityId) ORDER BY id",
    )
    suspend fun resumableSingles(identityId: Long?): List<AppliedChangeEntity>

    @Query("SELECT * FROM applied_change WHERE identity_id = :identityId AND state IN ('pending', 'streaming', 'streamed', 'failed') ORDER BY id DESC")
    suspend fun resumableForTrack(identityId: Long): List<AppliedChangeEntity>

    @Query("SELECT * FROM applied_change WHERE identity_id IN (:identityIds) AND state IN ('pending', 'streaming', 'streamed', 'verified')")
    suspend fun observedWrites(identityIds: List<Long>): List<AppliedChangeEntity>

    @Query("SELECT * FROM applied_change WHERE identity_id = :identityId AND state = 'verified' ORDER BY id DESC")
    suspend fun forTrack(identityId: Long): List<AppliedChangeEntity>

    @Query("SELECT * FROM applied_change WHERE batch_uuid = :batchUuid AND state = 'verified' ORDER BY id DESC")
    suspend fun forBatch(batchUuid: ByteArray): List<AppliedChangeEntity>

    @Query("SELECT EXISTS (SELECT 1 FROM applied_change WHERE state = 'verified' LIMIT 1 OFFSET :offset)")
    suspend fun hasVerifiedPage(offset: Int): Boolean

    @Query("SELECT * FROM applied_change WHERE state = 'verified' ORDER BY id LIMIT :limit")
    suspend fun oldestVerified(limit: Int): List<AppliedChangeEntity>

    @Query("SELECT * FROM tag_backup WHERE id IN (:ids)")
    suspend fun backups(ids: List<Long>): List<TagBackupEntity>

    @Query("DELETE FROM applied_change WHERE state = 'verified' AND id IN (:ids)")
    suspend fun deleteVerified(ids: List<Long>): Int

    @Query("DELETE FROM tag_backup WHERE id IN (:ids)")
    suspend fun deleteBackups(ids: List<Long>): Int

    @Query("UPDATE applied_change SET state = 'abandoned' WHERE id = :id AND batch_uuid IS NULL AND state = 'pending'")
    suspend fun abandonPending(id: Long): Int

    @Query("UPDATE applied_change SET undone_at = :at WHERE id = :id AND state = 'verified' AND undone_at IS NULL")
    suspend fun markUndone(id: Long, at: Long): Int

    @Query("UPDATE applied_change SET state = 'streaming' WHERE id = :id AND state IN ('pending', 'streaming', 'streamed', 'failed')")
    suspend fun markStreaming(id: Long): Int

    @Query("UPDATE applied_change SET state = 'streamed' WHERE id = :id AND state IN ('pending', 'streaming', 'streamed')")
    suspend fun markStreamed(id: Long): Int

    @Query(
        "UPDATE applied_change SET state = 'verified', actual_size = :size, actual_mtime = :mtime, " +
            "verified_at = :at WHERE id = :id AND state = 'streamed'",
    )
    suspend fun markVerified(id: Long, size: Long, mtime: Long, at: Long): Int

    @Query("UPDATE applied_change SET diff = :diff WHERE id = :id AND state = 'verified'")
    suspend fun saveVerifiedReferences(id: Long, diff: ByteArray)

    @Query("UPDATE applied_change SET state = 'failed' WHERE id = :id AND state IN ('pending', 'streamed')")
    suspend fun markFailed(id: Long): Int
}
