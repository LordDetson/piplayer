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
    // date_added for Recently added (docs/library.md §17.4).
    indices = [Index("media_store_id"), Index("volume_name", "relative_path"), Index("content_key"), Index("date_added")],
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
    /** When the media index added the file, in seconds, for Recently added (docs/library.md §17.1). */
    @ColumnInfo(name = "date_added", defaultValue = "0") val dateAdded: Long = 0,
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

/** The one row of what the library database last mirrored and derived (TI §10.1, docs/library.md §3.1). */
@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    /** The user database's `rules_counter` the folder rules of `audio_file` were last applied at (docs/library.md §3.4). */
    @ColumnInfo(name = "mirrored_rules_counter", defaultValue = "0") val mirroredRulesCounter: Long = 0,
    /** The user database's `change_counter` at which `decided_mirror` was last filled (TI §10.3). */
    @ColumnInfo(name = "mirrored_change_counter", defaultValue = "0") val mirroredChangeCounter: Long = 0,
    /** The version of the rules `track_display` was derived by (`TrackDisplayWriter.VERSION`). */
    @ColumnInfo(name = "display_version", defaultValue = "0") val displayVersion: Int = 0,
    /** A random ID made with the database; file IDs mean something only under it (docs/library.md §8.4). */
    @ColumnInfo(name = "library_id") val libraryId: String? = null,
    /** The highest file ID ever given, so that no ID goes to a second file (docs/library.md §8.4). */
    @ColumnInfo(name = "last_file_id", defaultValue = "0") val lastFileId: Long = 0,
    /** The collation the sort keys were made by (`Collation.name`, docs/library.md §11.1). */
    val collation: String? = null,
)

/** A cached fingerprint (TI §10.1, docs/library.md §8.3): the raw items of Chromaprint, by the key of their file. */
@Entity(tableName = "fingerprint")
data class FingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
    /** The raw items as 4-byte big-endian integers. */
    val fingerprint: ByteArray,
    /** The file's format, which re-attachment step 4 compares (TI §3.1). */
    val format: String?,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    /** The AcoustID track ID of the best result of a lookup (TI §3.2); null before one. */
    @ColumnInfo(name = "acoustid_id") val acoustIdId: String? = null,
    /** How the audio was decoded for the fingerprint (TI §3.2); null when not recorded. */
    @ColumnInfo(name = "decoder_path") val decoderPath: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is FingerprintEntity && contentKey == other.contentKey &&
        fingerprint.contentEquals(other.fingerprint) && format == other.format && computedAt == other.computedAt &&
        acoustIdId == other.acoustIdId && decoderPath == other.decoderPath

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
// The reasons for Songs missing information, looked up by each stored set (docs/library.md §17.4).
@Entity(tableName = "track_display", indices = [Index("title_sort", "search_text"), Index("album_key"), Index("missing_info_reasons")])
data class TrackDisplayEntity(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "search_text") val searchText: String,
    /** The reasons of `MergedValues`, null until the tag pass has read the file. */
    @ColumnInfo(name = "missing_info_reasons") val missingInfoReasons: Int? = null,
    /** What groups the file (docs/library.md §6.2); `artist_key` is the normalized name, not an order. */
    @ColumnInfo(name = "album_key") val albumKey: String? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(defaultValue = "0") val compilation: Boolean = false,
    val year: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "artist_key") val artistKey: String? = null,
    @ColumnInfo(name = "album_search", defaultValue = "") val albumSearch: String = "",
    /** The collator's keys (docs/library.md §11.1), from the sort tags once read. */
    @ColumnInfo(name = "title_sort", typeAffinity = ColumnInfo.BLOB) val titleSort: ByteArray,
    @ColumnInfo(name = "artist_sort", typeAffinity = ColumnInfo.BLOB) val artistSort: ByteArray? = null,
    @ColumnInfo(name = "album_sort", typeAffinity = ColumnInfo.BLOB) val albumSort: ByteArray? = null,
    @ColumnInfo(name = "album_artist_sort", typeAffinity = ColumnInfo.BLOB) val albumArtistSort: ByteArray? = null,
    @ColumnInfo(name = "file_sort", typeAffinity = ColumnInfo.BLOB) val fileSort: ByteArray,
    /** Keep as-is or Skip from the mirrored decisions (TI §1): [DECISION_NONE] until one is mirrored. */
    @ColumnInfo(name = "missing_info_decision", defaultValue = "0") val missingInfoDecision: Int = DECISION_NONE,
    /** The metadata badge (TI §6) from the mirrored decisions: [BADGE_NONE], the file's own values, until one is mirrored. */
    @ColumnInfo(name = "badge_state", defaultValue = "0") val badgeState: Int = BADGE_NONE,
) {
    companion object {
        const val DECISION_NONE = 0
        const val DECISION_KEEP_AS_IS = 1
        const val DECISION_SKIPPED = 2

        /** No decision wins: the values come from the file (the badge's "from tags"); M3 step 12 adds the others. */
        const val BADGE_NONE = 0
    }
}

/**
 * An artist of a file as lists group it (docs/library.md §6.2): each artist of its tags once read, otherwise the displayed
 * one; [artistKey] is the normalized name, which the artist's page is found by.
 */
@Entity(tableName = "display_artist", primaryKeys = ["file_id", "position"], indices = [Index("artist_key")])
data class DisplayArtistEntity(
    @ColumnInfo(name = "file_id") val fileId: Long,
    val position: Int,
    @ColumnInfo(name = "artist_key") val artistKey: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    /** The collator's key of the name, or of the artist's sort tag (docs/library.md §11.1). */
    @ColumnInfo(name = "sort_key", typeAffinity = ColumnInfo.BLOB) val sortKey: ByteArray,
)

/**
 * A genre of a file as lists group it (docs/library.md §19.1): each genre of its tags once read, otherwise the media
 * index's genre split at ";"; [genreKey] is the normalized name, which the genre's page is found by.
 */
@Entity(tableName = "display_genre", primaryKeys = ["file_id", "position"], indices = [Index("genre_key")])
data class DisplayGenreEntity(
    @ColumnInfo(name = "file_id") val fileId: Long,
    val position: Int,
    @ColumnInfo(name = "genre_key") val genreKey: String,
    val name: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    /** The collator's key of the name (docs/library.md §11.1). */
    @ColumnInfo(name = "sort_key", typeAffinity = ColumnInfo.BLOB) val sortKey: ByteArray,
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
        DisplayArtistEntity::class,
        DisplayGenreEntity::class,
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
        TrackMirrorEntity::class,
        AlbumMirrorEntity::class,
        LookupJobEntity::class,
        CandidateEntity::class,
        IdentificationTraceEntity::class,
        ReleaseCacheEntity::class,
        ApiCacheEntity::class,
        DecidedMirrorEntity::class,
    ],
    // 10: the mirror of favorites and plays (docs/library.md §15.4). 11: the smart lists' date added and indexes (§17.4).
    // 12: the genres of display rows (§19.5). 13: identification (TI §10.1): the content-key tables, the release cache,
    // api_cache, decided_mirror, the identification columns of fingerprint and track_display, and both mirrored counters.
    version = 13,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun library(): LibraryDao

    abstract fun mirror(): MirrorDao

    /** The release cache and `api_cache` (TI §10.1). */
    abstract fun identificationCache(): IdentificationCacheDao

    /** The decided values beside `track_display` (TI §10.1). */
    abstract fun decided(): DecidedMirrorDao

    /** The lookup jobs, candidates and trace of identification, by content key (TI §10.1). */
    abstract fun identificationRuns(): IdentificationRunDao

    companion object {
        const val FILE_NAME = "library.db"

        /** The reader connections of a database file, Room's own number for write-ahead logging, set here by name. */
        const val READERS = 4

        /** The database in [file], or in memory when it is null (tests). */
        fun open(context: Context, file: File?): LibraryDatabase {
            val builder = if (file == null) {
                Room.inMemoryDatabaseBuilder<LibraryDatabase>(context)
            } else {
                Room.databaseBuilder<LibraryDatabase>(context, file.absolutePath).setMultipleConnectionPool(READERS, 1)
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
