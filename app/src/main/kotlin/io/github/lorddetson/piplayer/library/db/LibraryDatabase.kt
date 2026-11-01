package io.github.lorddetson.piplayer.library.db

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * An audio row of the media index (docs/library.md §1.2, TI §10.1). [id] is PiPlayer's own, given by the sync; it
 * outlives index IDs.
 */
// Not unique: within one sync's transaction an index rebuild can hand a stored ID to another file before its old owner
// has taken its new one.
@Entity(tableName = "audio_file", indices = [Index("media_store_id"), Index("volume_name", "relative_path")])
data class AudioFileEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val size: Long,
    val mtime: Long,
    @ColumnInfo(name = "generation_modified") val generationModified: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "is_music") val isMusic: Boolean,
    @ColumnInfo(name = "is_podcast") val isPodcast: Boolean,
    @ColumnInfo(name = "is_audiobook") val isAudiobook: Boolean,
    @ColumnInfo(name = "is_ringtone") val isRingtone: Boolean,
    @ColumnInfo(name = "is_notification") val isNotification: Boolean,
    @ColumnInfo(name = "is_alarm") val isAlarm: Boolean,
    /** Null on Android 11, whose index has no such flag. */
    @ColumnInfo(name = "is_recording") val isRecording: Boolean?,
    /** Whether the file counts as a recording: the index's flag, or on Android 11 a `Recordings` folder (§3.5). */
    val recording: Boolean,
    /** The rule of the file's folder as the user database had it when it was mirrored (§3.4), a `FolderRuleState`. */
    @ColumnInfo(name = "folder_rule") val folderRule: Int,
)

/** The user database's change counter that the folder rules of `audio_file` mirror (docs/library.md §3.4). */
@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "mirrored_user_counter") val mirroredUserCounter: Long,
)

/** The media index's values for a file, as it gives them (TI §10.1, precedence level 4). */
@Entity(tableName = "media_index_entry")
data class MediaIndexEntryEntity(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "album_artist") val albumArtist: String?,
    val composer: String?,
    val genre: String?,
    val year: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
)

/** What lists show, sort and search by (docs/library.md §1.2). */
// Covering the search text too: a search walks this index in title order and never reads the table for rows that do
// not match (M0 spike).
@Entity(tableName = "track_display", indices = [Index("title_key", "search_text")])
data class TrackDisplayEntity(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "title_key") val titleKey: String,
    @ColumnInfo(name = "search_text") val searchText: String,
)

/** How far the last sync of a volume got (docs/library.md §1.3). */
@Entity(tableName = "volume_sync_state")
data class VolumeSyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "volume_name") val volumeName: String,
    val version: String,
    val generation: Long,
)

/**
 * The library database (docs/library.md §1.1): rebuildable, so a new schema version drops every table and the next
 * sync fills them again.
 */
@Database(
    entities = [
        AudioFileEntity::class,
        MediaIndexEntryEntity::class,
        TrackDisplayEntity::class,
        VolumeSyncStateEntity::class,
        LibraryMetaEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun library(): LibraryDao

    companion object {
        const val FILE_NAME = "library.db"

        /** The database in [file], or in memory when it is null (tests). */
        fun open(context: Context, file: File?): LibraryDatabase {
            val builder = if (file == null) {
                Room.inMemoryDatabaseBuilder<LibraryDatabase>(context)
            } else {
                Room.databaseBuilder<LibraryDatabase>(context, file.absolutePath)
            }
            return builder
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
