package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.Query

/** A selected decision at the point the user starts applying a batch. Write permission is never persisted here. */
@Entity(tableName = "tag_apply_item", primaryKeys = ["plan_id", "file_id"], indices = [Index(value = ["write_id"], unique = true)])
data class TagApplyItem(
    @ColumnInfo(name = "plan_id") val planId: String,
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "decision_id") val decisionId: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "write_id") val writeId: Long? = null,
    val result: String = "queued",
)

internal class TagApplyQueue(private val database: UserDatabase) {
    private val dao get() = database.tagApplyQueue()

    suspend fun save(items: List<TagApplyItem>) {
        val plan = items.firstOrNull()?.planId ?: return
        require(plan.isNotBlank() && items.all { it.planId == plan && it.result == "queued" && it.writeId == null })
        dao.insert(items)
    }

    suspend fun finish(item: TagApplyItem, result: String): Boolean {
        require(result in setOf("written", "skipped", "failed"))
        return dao.finish(item.planId, item.fileId, result) == 1
    }

    suspend fun remaining(planId: String): List<TagApplyItem> = dao.remaining(planId)

    suspend fun results(planId: String): Map<String, Int> = dao.counts(planId).associate { it.result to it.count }
}

data class TagApplyCount(val result: String, val count: Int)

@Dao
interface TagApplyDao {
    @Insert
    suspend fun insert(items: List<TagApplyItem>)

    @Query("SELECT * FROM tag_apply_item WHERE plan_id = :planId AND result = 'queued' ORDER BY file_id")
    suspend fun remaining(planId: String): List<TagApplyItem>

    @Query("SELECT * FROM tag_apply_item WHERE plan_id = :planId AND file_id = :fileId")
    suspend fun item(planId: String, fileId: Long): TagApplyItem?

    @Query(
        "UPDATE tag_apply_item SET write_id = :writeId " +
            "WHERE plan_id = :planId AND file_id = :fileId AND result = 'queued' AND write_id IS NULL",
    )
    suspend fun attachWrite(planId: String, fileId: Long, writeId: Long): Int

    @Query("UPDATE tag_apply_item SET result = 'written' WHERE write_id = :writeId AND result = 'queued'")
    suspend fun markWritten(writeId: Long): Int

    @Query("UPDATE tag_apply_item SET result = :result WHERE plan_id = :planId AND file_id = :fileId AND result = 'queued'")
    suspend fun finish(planId: String, fileId: Long, result: String): Int

    @Query("SELECT result, COUNT(*) AS count FROM tag_apply_item WHERE plan_id = :planId GROUP BY result")
    suspend fun counts(planId: String): List<TagApplyCount>
}
