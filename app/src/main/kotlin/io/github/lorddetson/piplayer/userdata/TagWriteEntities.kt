package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Snapshot of the tag data before a write (TI §7.2). The files live in no-backup storage. */
@Entity(tableName = "tag_backup", indices = [Index("identity_id")])
data class TagBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "tags_path") val tagsPath: String,
    @ColumnInfo(name = "tags_hash") val tagsHash: ByteArray,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "cover_hash") val coverHash: ByteArray?,
    @ColumnInfo(name = "id3_version") val id3Version: Int?,
)

/** A verified working copy and the recorded result of trying to write it back (TI §7.2 and §10.2). */
@Entity(
    tableName = "applied_change",
    indices = [
        Index("identity_id"), Index("batch_uuid"),
        Index(value = ["volume_name", "relative_path", "display_name"]),
    ],
)
data class AppliedChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "decision_id") val decisionId: Long?,
    @ColumnInfo(name = "batch_uuid") val batchUuid: ByteArray?,
    @ColumnInfo(name = "backup_id") val backupId: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val state: String,
    @ColumnInfo(name = "copy_path") val copyPath: String,
    @ColumnInfo(name = "copy_size") val copySize: Long,
    @ColumnInfo(name = "copy_sha256") val copySha256: ByteArray,
    @ColumnInfo(name = "expected_content_key") val expectedContentKey: ByteArray,
    @ColumnInfo(name = "tag_hash") val tagHash: ByteArray,
    val diff: ByteArray,
    @ColumnInfo(name = "actual_size") val actualSize: Long? = null,
    @ColumnInfo(name = "actual_mtime") val actualMtime: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,
    @ColumnInfo(name = "undone_at") val undoneAt: Long? = null,
)
