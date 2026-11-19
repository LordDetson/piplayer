package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.tagging.write.ArchivedTagWrite
import io.github.lorddetson.piplayer.tagging.write.TagWriteArchiveCodec
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecord

@Entity(tableName = "tag_write_archive")
data class TagWriteArchivePage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val payload: ByteArray,
)

@Entity(tableName = "tag_write_archive_ref", indices = [Index("identity_id")])
data class TagWriteArchiveRef(
    @PrimaryKey @ColumnInfo(name = "change_id") val changeId: Long,
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "backup_id") val backupId: Long,
    @ColumnInfo(name = "page_id") val pageId: Long,
)

@Entity(tableName = "tag_write_archive_batch", primaryKeys = ["batch_uuid", "page_id"])
data class TagWriteArchiveBatch(
    @ColumnInfo(name = "batch_uuid") val batchUuid: ByteArray,
    @ColumnInfo(name = "page_id") val pageId: Long,
)

@Dao
interface TagWriteArchiveDao {
    @Insert
    suspend fun add(page: TagWriteArchivePage): Long

    @Insert
    suspend fun addRefs(refs: List<TagWriteArchiveRef>)

    @Insert
    suspend fun addBatches(batches: List<TagWriteArchiveBatch>)

    @Query("SELECT * FROM tag_write_archive WHERE id = (SELECT page_id FROM tag_write_archive_ref WHERE change_id = :changeId)")
    suspend fun pageForChange(changeId: Long): TagWriteArchivePage?

    @Query("SELECT * FROM tag_write_archive WHERE id = (SELECT page_id FROM tag_write_archive_ref WHERE backup_id = :backupId)")
    suspend fun pageForBackup(backupId: Long): TagWriteArchivePage?

    @Query("SELECT * FROM tag_write_archive WHERE id IN (SELECT page_id FROM tag_write_archive_ref WHERE identity_id IN (:ids))")
    suspend fun pagesForIdentities(ids: List<Long>): List<TagWriteArchivePage>

    @Query("SELECT * FROM tag_write_archive WHERE id IN (SELECT page_id FROM tag_write_archive_batch WHERE batch_uuid = :uuid)")
    suspend fun pagesForBatch(uuid: ByteArray): List<TagWriteArchivePage>

    @Query("UPDATE tag_write_archive SET payload = :payload WHERE id = :pageId")
    suspend fun update(pageId: Long, payload: ByteArray): Int
}

/** Packs completed records without moving the copies or state needed for interrupted writes. */
internal class TagWriteArchive(private val database: UserDatabase) {
    private val archive get() = database.tagWriteArchive()
    private val live get() = database.tagWrites()

    suspend fun entry(id: Long): AppliedChangeEntity? =
        archive.pageForChange(id)?.records()?.firstOrNull { it.entry.id == id }?.entry

    suspend fun backup(id: Long): TagBackupEntity? =
        archive.pageForBackup(id)?.records()?.firstOrNull { it.backup.id == id }?.backup

    suspend fun forIdentities(ids: Collection<Long>): List<AppliedChangeEntity> {
        val wanted = ids.toSet()
        return archive.pagesForIdentities(wanted.toList()).flatMap { page ->
            page.records().map { it.entry }.filter { it.identityId in wanted }
        }
    }

    suspend fun forBatch(uuid: ByteArray): List<AppliedChangeEntity> =
        archive.pagesForBatch(uuid).flatMap { page ->
            page.records().map { it.entry }.filter { it.batchUuid.contentEquals(uuid) }
        }

    /** The caller holds the same transaction that sets the reversal to verified. */
    suspend fun markUndone(id: Long, at: Long): Boolean {
        val page = archive.pageForChange(id) ?: return false
        val records = page.records().toMutableList()
        val index = records.indexOfFirst { it.entry.id == id && it.entry.undoneAt == null }
        if (index < 0) return false
        val original = records[index]
        records[index] = original.copy(entry = original.entry.copy(undoneAt = at))
        return archive.update(page.id, TagWriteArchiveCodec.encode(records)) == 1
    }

    /** Called inside a write transaction. A malformed historical row stays in the live journal. */
    suspend fun compactPage(): Boolean {
        if (!live.hasVerifiedPage(PAGE_SIZE - 1)) return false
        val candidates = live.oldestVerified(PAGE_SIZE)
        if (candidates.size < PAGE_SIZE) return false
        val backups = live.backups(candidates.map { it.backupId }).associateBy { it.id }
        val records = candidates.map { entry ->
            val backup = backups[entry.backupId] ?: return false
            if (entry.actualSize == null || TagWriteRecord.decode(entry.diff) == null) return false
            ArchivedTagWrite(entry, backup)
        }
        val pageId = archive.add(TagWriteArchivePage(payload = TagWriteArchiveCodec.encode(records)))
        archive.addRefs(records.map { record ->
            TagWriteArchiveRef(record.entry.id, record.entry.identityId, record.backup.id, pageId)
        })
        val batches = candidates.mapNotNull { it.batchUuid }.distinctBy(Hex::text)
        if (batches.isNotEmpty()) {
            archive.addBatches(batches.map { uuid -> TagWriteArchiveBatch(uuid, pageId) })
        }
        check(live.deleteVerified(candidates.map { it.id }) == PAGE_SIZE)
        check(live.deleteBackups(backups.keys.toList()) == PAGE_SIZE)
        return true
    }

    private fun TagWriteArchivePage.records() = TagWriteArchiveCodec.decode(payload)

    private companion object {
        const val PAGE_SIZE = 64
    }
}
