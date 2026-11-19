package io.github.lorddetson.piplayer.userdata

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.withWriteTransaction

/** Records the verified working copy and its snapshot together before the original can be opened for writing. */
internal class TagWriteJournal(private val database: UserDatabase) {
    private val dao get() = database.tagWrites()

    suspend fun prepare(backup: TagBackupEntity, change: AppliedChangeEntity): Long = database.withWriteTransaction {
        require(change.id == 0L && backup.id == 0L && change.backupId == 0L && change.state == "pending")
        require(change.identityId == backup.identityId && change.volumeName == backup.volumeName &&
            change.relativePath == backup.relativePath && change.displayName == backup.displayName)
        dao.insertChange(change.copy(backupId = dao.insertBackup(backup)))
    }

    suspend fun entry(id: Long): AppliedChangeEntity? = dao.entry(id)
    suspend fun backup(id: Long): TagBackupEntity? = dao.backup(id)
    suspend fun interrupted(): List<AppliedChangeEntity> = dao.interrupted()
    suspend fun streamed(id: Long): Boolean = dao.markStreamed(id) == 1
    suspend fun verified(id: Long, size: Long, mtime: Long, at: Long): Boolean = dao.markVerified(id, size, mtime, at) == 1
}

@Dao
interface TagWriteDao {
    @Insert
    suspend fun insertBackup(backup: TagBackupEntity): Long

    @Insert
    suspend fun insertChange(change: AppliedChangeEntity): Long

    @Query("SELECT * FROM applied_change WHERE id = :id")
    suspend fun entry(id: Long): AppliedChangeEntity?

    @Query("SELECT * FROM tag_backup WHERE id = :id")
    suspend fun backup(id: Long): TagBackupEntity?

    @Query("SELECT * FROM applied_change WHERE state IN ('pending', 'streamed') ORDER BY id")
    suspend fun interrupted(): List<AppliedChangeEntity>

    @Query("UPDATE applied_change SET state = 'streamed' WHERE id = :id AND state IN ('pending', 'streamed')")
    suspend fun markStreamed(id: Long): Int

    @Query(
        "UPDATE applied_change SET state = 'verified', actual_size = :size, actual_mtime = :mtime, " +
            "verified_at = :at WHERE id = :id AND state = 'streamed'",
    )
    suspend fun markVerified(id: Long, size: Long, mtime: Long, at: Long): Int
}
