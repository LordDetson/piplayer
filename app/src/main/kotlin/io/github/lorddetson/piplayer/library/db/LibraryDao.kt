package io.github.lorddetson.piplayer.library.db

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
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

    /** The songs of the library in list order, without the hidden files (docs/library.md §1.4, §3.5). */
    @Query("$SONGS ORDER BY d.title_key, f.media_store_id")
    suspend fun songs(showSystemSounds: Boolean, showRecordings: Boolean, minLengthMs: Long): List<SongRow>

    /** The first [count] songs of [songs]. */
    @Query("$SONGS ORDER BY d.title_key, f.media_store_id LIMIT :count")
    suspend fun firstSongs(showSystemSounds: Boolean, showRecordings: Boolean, minLengthMs: Long, count: Int): List<SongRow>

    /** Every folder that holds a file, with its volume. */
    @Query("SELECT DISTINCT volume_name AS volume, relative_path AS relativePath FROM audio_file")
    suspend fun folders(): List<FolderKey>

    /** Every folder with each rule its files carry; a folder whose files differ comes once per rule. */
    @Query("SELECT DISTINCT volume_name AS volume, relative_path AS relativePath, folder_rule AS rule FROM audio_file")
    suspend fun folderRules(): List<FolderRuleRow>

    @Query("UPDATE audio_file SET folder_rule = :rule WHERE volume_name = :volume AND relative_path = :relativePath")
    suspend fun setFolderRule(volume: String, relativePath: String, rule: Int)

    @Query("SELECT COALESCE((SELECT mirrored_user_counter FROM library_meta WHERE id = 1), 0)")
    suspend fun mirroredUserCounter(): Long

    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter) VALUES (1, :counter) " +
            "ON CONFLICT(id) DO UPDATE SET mirrored_user_counter = :counter",
    )
    suspend fun setMirroredUserCounter(counter: Long)

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

    @Insert
    suspend fun addTrack(track: TrackEntity): Long

    @Query("SELECT * FROM track")
    suspend fun tracks(): List<TrackEntity>

    @Query("SELECT COALESCE((SELECT display_version FROM library_meta WHERE id = 1), 0)")
    suspend fun displayVersion(): Int

    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter, display_version) VALUES (1, 0, :version) " +
            "ON CONFLICT(id) DO UPDATE SET display_version = :version",
    )
    suspend fun setDisplayVersion(version: Int)

    /** What the tag pass found in a file. */
    @Query(
        "UPDATE audio_file SET tag_pass_at = :at, reader_version = :readerVersion, tag_failure = NULL, format = :format, " +
            "codec = :codec, sample_rate = :sampleRate, bit_depth = :bitDepth, channels = :channels, bitrate = :bitrate, " +
            "tag_encoding = :tagEncoding, embedded_cover_present = :coverPresent, embedded_cover_bytes = :coverBytes, " +
            "tag_length_ms = :tagLengthMs WHERE id = :fileId",
    )
    suspend fun markRead(
        fileId: Long,
        at: Long,
        readerVersion: Int,
        format: String?,
        codec: String?,
        sampleRate: Int?,
        bitDepth: Int?,
        channels: Int?,
        bitrate: Int?,
        tagEncoding: String?,
        coverPresent: Boolean,
        coverBytes: Long?,
        tagLengthMs: Long?,
    )

    /** The tracks the pass has read in [volume], with what the display needs of their files. */
    @Query("$READ_FILES WHERE f.volume_name = :volume AND f.tag_pass_at IS NOT NULL")
    suspend fun readFilesIn(volume: String): List<ReadFileRow>

    @Query("$READ_FILES WHERE f.tag_pass_at IS NOT NULL")
    suspend fun readFiles(): List<ReadFileRow>

    @Query("$READ_FILES WHERE f.tag_pass_at IS NOT NULL AND f.id IN (:fileIds)")
    suspend fun readFilesOf(fileIds: List<Long>): List<ReadFileRow>

    @Query(INDEX_INPUTS)
    suspend fun indexInputs(): List<IndexInputRow>

    @Query("$INDEX_INPUTS WHERE f.id IN (:fileIds)")
    suspend fun indexInputsOf(fileIds: List<Long>): List<IndexInputRow>

    @Query("DELETE FROM track_artist WHERE track_id IN (SELECT id FROM track WHERE file_id IN (:fileIds))")
    suspend fun deleteTrackArtists(fileIds: List<Long>)

    @Query("DELETE FROM track_genre WHERE track_id IN (SELECT id FROM track WHERE file_id IN (:fileIds))")
    suspend fun deleteTrackGenres(fileIds: List<Long>)

    @Query("DELETE FROM track WHERE file_id IN (:fileIds)")
    suspend fun deleteTracks(fileIds: List<Long>)
}

/** A read track with what the display needs of its file. */
data class ReadFileRow(
    @Embedded val track: TrackEntity,
    val size: Long,
    val mtime: Long,
    val displayName: String,
    val embeddedCover: Boolean?,
    val tagLengthMs: Long?,
    val exactDurationMs: Long?,
)

/** What the display needs of a file's index values. */
data class IndexInputRow(
    val fileId: Long,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val composer: String?,
    val genre: String?,
    val durationMs: Long?,
)

/** A folder of a volume, as the media index gives its path. */
data class FolderKey(val volume: String, val relativePath: String)

/** A folder with a rule its files carry. */
data class FolderRuleRow(val volume: String, val relativePath: String, val rule: Int)

private const val READ_FILES = "SELECT t.*, f.size, f.mtime, f.display_name AS displayName, " +
    "f.embedded_cover_present AS embeddedCover, f.tag_length_ms AS tagLengthMs, f.duration_ms AS exactDurationMs " +
    "FROM track t JOIN audio_file f ON f.id = t.file_id"

private const val INDEX_INPUTS = "SELECT f.id AS fileId, f.display_name AS displayName, e.title, e.artist, e.album, " +
    "e.composer, e.genre, e.duration_ms AS durationMs FROM audio_file f LEFT JOIN media_index_entry e ON e.file_id = f.id"

// Excluded folders never; included folders whole; elsewhere every file but the hidden kinds and short files. A file
// whose length is unknown is never short (docs/library.md §3.5).
private const val SONGS = "SELECT f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, d.duration_ms AS durationMs " +
    "FROM track_display d JOIN audio_file f ON f.id = d.file_id " +
    "WHERE f.folder_rule != 2 AND (f.folder_rule = 1 OR (" +
    "(:showSystemSounds OR NOT (f.is_ringtone OR f.is_notification OR f.is_alarm)) " +
    "AND (:showRecordings OR NOT f.recording) " +
    "AND (d.duration_ms IS NULL OR d.duration_ms >= :minLengthMs)))"
