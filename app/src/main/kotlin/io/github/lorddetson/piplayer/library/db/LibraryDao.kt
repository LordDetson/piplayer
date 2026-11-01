package io.github.lorddetson.piplayer.library.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.lorddetson.piplayer.library.sync.StoredFile

/** A song as lists show it, with the media index's ID. */
data class SongRow(
    val mediaStoreId: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

@Dao
interface LibraryDao {

    @Query("SELECT * FROM volume_sync_state")
    suspend fun volumes(): List<VolumeSyncStateEntity>

    @Upsert
    suspend fun saveVolume(state: VolumeSyncStateEntity)

    @Query("DELETE FROM volume_sync_state WHERE volume_name = :volume")
    suspend fun forgetVolume(volume: String)

    @Query(
        "SELECT id AS fileId, media_store_id AS mediaStoreId, relative_path AS relativePath, " +
            "display_name AS displayName FROM audio_file WHERE volume_name = :volume",
    )
    suspend fun storedFiles(volume: String): List<StoredFile>

    /** The highest row ID in use, or 0; new files take the next ones, which bulk inserts need to know up front. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM audio_file")
    suspend fun lastFileId(): Long

    @Query("DELETE FROM audio_file WHERE id IN (:fileIds)")
    suspend fun deleteFiles(fileIds: List<Long>)

    @Query("DELETE FROM media_index_entry WHERE file_id IN (:fileIds)")
    suspend fun deleteEntries(fileIds: List<Long>)

    @Query("DELETE FROM track_display WHERE file_id IN (:fileIds)")
    suspend fun deleteDisplays(fileIds: List<Long>)

    /** The music of the library in list order (docs/library.md §1.4). */
    @Query("$SONGS ORDER BY d.title_key, f.media_store_id")
    suspend fun songs(): List<SongRow>

    /** The first [count] songs of [songs]. */
    @Query("$SONGS ORDER BY d.title_key, f.media_store_id LIMIT :count")
    suspend fun firstSongs(count: Int): List<SongRow>

    @Query("SELECT * FROM audio_file")
    suspend fun files(): List<AudioFileEntity>

    /** Null when there is no such file. */
    @Query("SELECT unsafe_count FROM audio_file WHERE id = :fileId")
    suspend fun unsafeCount(fileId: Long): Int?

    @Query("UPDATE audio_file SET unsafe_count = :count WHERE id = :fileId")
    suspend fun setUnsafeCount(fileId: Long, count: Int)

    @Query("SELECT * FROM outside_file WHERE uri = :uri")
    suspend fun outsideFile(uri: String): OutsideFileEntity?

    @Upsert
    suspend fun saveOutsideFile(file: OutsideFileEntity)

    @Query("DELETE FROM outside_file WHERE uri = :uri")
    suspend fun forgetOutsideFile(uri: String)

    @Query("SELECT * FROM media_index_entry")
    suspend fun entries(): List<MediaIndexEntryEntity>

    @Query("SELECT * FROM track_display")
    suspend fun displays(): List<TrackDisplayEntity>
}

private const val SONGS = "SELECT f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, d.duration_ms AS durationMs " +
    "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE f.is_music"
