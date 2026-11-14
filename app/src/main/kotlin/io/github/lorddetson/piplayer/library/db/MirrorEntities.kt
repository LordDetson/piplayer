package io.github.lorddetson.piplayer.library.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * The mirrored user fields of a file (TI §10.1, docs/library.md §15.4): a row only for a file whose identity is a
 * favorite or has plays. Kept beside `track_display`, which other writers replace whole.
 */
@Entity(tableName = "track_mirror", indices = [Index("last_played_at"), Index("play_count")])
data class TrackMirrorEntity(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: Long,
    val favorite: Boolean,
    @ColumnInfo(name = "play_count") val playCount: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
)

/** A favorite album, mirrored (docs/library.md §15.4). */
@Entity(tableName = "album_mirror")
data class AlbumMirrorEntity(@PrimaryKey @ColumnInfo(name = "album_key") val albumKey: String)

/** A file of a referenced album with its content key (docs/library.md §15.3). */
data class AlbumFileRow(
    @ColumnInfo(name = "album_key") val albumKey: String,
    @ColumnInfo(name = "content_key") val contentKey: String,
)

/** A file's content key, and the album it belongs to now. */
data class KeyAlbumRow(
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "album_key") val albumKey: String?,
)

/** A file's content key. */
data class FileKeyRow(
    @ColumnInfo(name = "id") val fileId: Long,
    @ColumnInfo(name = "content_key") val contentKey: String?,
)

/**
 * Reads of the mirror's tables and of what it needs (docs/library.md §15.4); its writes go in bulk through `UserMirror`.
 * Lists of arguments stay under SQLite's limit: callers bind at most [CHUNK] at a time.
 */
@Dao
interface MirrorDao {

    @Query("SELECT * FROM track_mirror ORDER BY file_id")
    suspend fun tracks(): List<TrackMirrorEntity>

    @Query("SELECT * FROM album_mirror ORDER BY album_key")
    suspend fun albums(): List<AlbumMirrorEntity>

    @Query("SELECT id, content_key FROM audio_file WHERE id IN (:fileIds)")
    suspend fun keysOf(fileIds: List<Long>): List<FileKeyRow>

    /** Which of [keys] name an album now: a file of the library has it, hidden or not. */
    @Query("SELECT DISTINCT album_key FROM track_display WHERE album_key IN (:keys)")
    suspend fun presentAlbums(keys: List<String>): List<String>

    /** The keyed files of the albums [keys]. */
    @Query(
        "SELECT d.album_key, f.content_key FROM track_display d JOIN audio_file f ON f.id = d.file_id " +
            "WHERE d.album_key IN (:keys) AND f.content_key IS NOT NULL",
    )
    suspend fun albumFiles(keys: List<String>): List<AlbumFileRow>

    /** The albums of the files that hold the content keys [keys]. */
    @Query(
        "SELECT f.content_key, d.album_key FROM audio_file f JOIN track_display d ON d.file_id = f.id " +
            "WHERE f.content_key IN (:keys)",
    )
    suspend fun albumsOfKeys(keys: List<String>): List<KeyAlbumRow>

    @Query("SELECT COALESCE((SELECT mirrored_data_counter FROM library_meta WHERE id = 1), -1)")
    suspend fun mirroredCounter(): Long

    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter, mirrored_data_counter) VALUES (1, 0, :counter) " +
            "ON CONFLICT(id) DO UPDATE SET mirrored_data_counter = :counter",
    )
    suspend fun setMirroredCounter(counter: Long)

    companion object {
        const val CHUNK = 500
    }
}
