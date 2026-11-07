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
@Entity(
    tableName = "audio_file",
    indices = [Index("media_store_id"), Index("volume_name", "relative_path"), Index("content_key")],
)
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
    /** Reads of the file that crashed or hung `:tagger`; at 2 it is not read any more (docs/library.md §4.4). */
    @ColumnInfo(name = "unsafe_count", defaultValue = "0") val unsafeCount: Int = 0,
    /** When the tag pass read the file; null until then. The values below hold while size and mtime stay the same. */
    @ColumnInfo(name = "tag_pass_at") val tagPassAt: Long? = null,
    /** The reader's version that last tried the file; a higher one tries it again. */
    @ColumnInfo(name = "reader_version") val readerVersion: Int? = null,
    /** Why the last read failed, if it did. */
    @ColumnInfo(name = "tag_failure") val tagFailure: String? = null,
    val format: String? = null,
    val codec: String? = null,
    @ColumnInfo(name = "sample_rate") val sampleRate: Int? = null,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    /** The legacy encoding the file's tags were read in (TI §3.6). */
    @ColumnInfo(name = "tag_encoding") val tagEncoding: String? = null,
    @ColumnInfo(name = "embedded_cover_present") val embeddedCoverPresent: Boolean? = null,
    @ColumnInfo(name = "embedded_cover_bytes") val embeddedCoverBytes: Long? = null,
    @ColumnInfo(name = "embedded_cover_width") val embeddedCoverWidth: Int? = null,
    @ColumnInfo(name = "embedded_cover_height") val embeddedCoverHeight: Int? = null,
    @ColumnInfo(name = "embedded_cover_hash") val embeddedCoverHash: String? = null,
    /** The length TagLib gives, an estimate for a VBR MP3 without a header. */
    @ColumnInfo(name = "tag_length_ms") val tagLengthMs: Long? = null,
    /** Written by the content key stage (TI §2). */
    @ColumnInfo(name = "content_key") val contentKey: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "duration_source") val durationSource: String? = null,
)

/** The user database's change counter that the folder rules of `audio_file` mirror (docs/library.md §3.4). */
@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "mirrored_user_counter") val mirroredUserCounter: Long,
    /** The version of the rules `track_display` was derived by (`TrackDisplayWriter.VERSION`). */
    @ColumnInfo(name = "display_version", defaultValue = "0") val displayVersion: Int = 0,
    /** A random ID made with the database; file IDs mean something only under it (docs/library.md §8.4). */
    @ColumnInfo(name = "library_id") val libraryId: String? = null,
    /** The highest file ID ever given, so that no ID goes to a second file (docs/library.md §8.4). */
    @ColumnInfo(name = "last_file_id", defaultValue = "0") val lastFileId: Long = 0,
)

/** A cached fingerprint (TI §10.1, docs/library.md §8.3): the raw items of Chromaprint, by the key of their file. */
@Entity(tableName = "fingerprint")
data class FingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
    /** The raw items as 4-byte big-endian integers. */
    val fingerprint: ByteArray,
    val format: String?,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is FingerprintEntity && contentKey == other.contentKey &&
        fingerprint.contentEquals(other.fingerprint) && format == other.format && computedAt == other.computedAt

    override fun hashCode(): Int = contentKey.hashCode()
}

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
    /** The reasons of `MergedValues`, null until the tag pass has read the file. */
    @ColumnInfo(name = "missing_info_reasons") val missingInfoReasons: Int? = null,
)

/**
 * A file outside the library that crashed or hung `:tagger` (docs/library.md §4.4); another size or modification time
 * makes it a new file.
 */
@Entity(tableName = "outside_file")
data class OutsideFileEntity(
    @PrimaryKey val uri: String,
    val size: Long,
    val mtime: Long,
    @ColumnInfo(name = "unsafe_count") val unsafeCount: Int,
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
        OutsideFileEntity::class,
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TrackArtistEntity::class,
        GenreEntity::class,
        TrackGenreEntity::class,
        FingerprintEntity::class,
    ],
    version = 7,
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
                // Not left to the device: on one with little memory the automatic choice gives a single connection, and
                // lists would wait for each batch of the tag pass.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
