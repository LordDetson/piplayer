package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Update

/**
 * A track identity (TI §10.2, docs/library.md §8.1): what all user data refers to. The content key is 32 bytes and the
 * recording MBID 16; the hints name the place of one of its files.
 */
@Entity(tableName = "track_identity", indices = [Index("content_key", "start_ms", unique = true)])
data class TrackIdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
    @ColumnInfo(name = "start_ms", defaultValue = "0") val startMs: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "recording_mbid") val recordingMbid: ByteArray?,
    @ColumnInfo(name = "hint_volume_name") val hintVolumeName: String?,
    @ColumnInfo(name = "hint_relative_path") val hintRelativePath: String?,
    @ColumnInfo(name = "hint_display_name") val hintDisplayName: String?,
    @ColumnInfo(name = "hint_size") val hintSize: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** When the last file that held the key went; null while one holds it. */
    @ColumnInfo(name = "missing_since") val missingSince: Long?,
) {
    // A data class compares arrays by reference; identities compare by their values.
    override fun equals(other: Any?): Boolean = other is TrackIdentityEntity && id == other.id &&
        contentKey.contentEquals(other.contentKey) && startMs == other.startMs && durationMs == other.durationMs &&
        recordingMbid.contentEquals(other.recordingMbid) && hintVolumeName == other.hintVolumeName &&
        hintRelativePath == other.hintRelativePath && hintDisplayName == other.hintDisplayName &&
        hintSize == other.hintSize && createdAt == other.createdAt && missingSince == other.missingSince

    override fun hashCode(): Int = id.hashCode() * 31 + contentKey.contentHashCode()
}

@Dao
interface TrackIdentityDao {

    @Query("SELECT * FROM track_identity ORDER BY id")
    suspend fun identities(): List<TrackIdentityEntity>

    @Query("SELECT * FROM track_identity WHERE id = :id")
    suspend fun identity(id: Long): TrackIdentityEntity?

    @Query("SELECT id FROM track_identity WHERE content_key = :contentKey AND start_ms = 0")
    suspend fun idOfKey(contentKey: ByteArray): Long?

    @Insert
    suspend fun add(identity: TrackIdentityEntity): Long

    @Insert
    suspend fun addAll(identities: List<TrackIdentityEntity>)

    @Update
    suspend fun updateAll(identities: List<TrackIdentityEntity>)

    @Query("DELETE FROM track_identity WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM track_identity")
    suspend fun deleteAll()
}
