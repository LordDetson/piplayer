package io.github.lorddetson.piplayer.library.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.lorddetson.piplayer.library.sync.StoredFile
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.util.UUID

/** A song as lists show it, with the file ID of PiPlayer and the ID of the media index. */
data class SongRow(
    val fileId: Long,
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

    /**
     * What the tag pass still has to read: the files of a lower reader version, those no version has settled yet, in list
     * order with the kinds that lists hide last; not those in excluded folders or marked unsafe (docs/library.md §5).
     */
    @Query(
        "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, f.volume_name AS volume, f.display_name AS displayName, " +
            "f.size, f.mtime FROM audio_file f LEFT JOIN track_display d ON d.file_id = f.id " +
            "WHERE (f.reader_version IS NULL OR f.reader_version < :readerVersion) AND f.unsafe_count < :unsafeCount " +
            "AND f.folder_rule != 2 " +
            "ORDER BY (f.is_ringtone OR f.is_notification OR f.is_alarm OR f.recording), d.title_key, f.id",
    )
    suspend fun unreadFiles(readerVersion: Int, unsafeCount: Int): List<PassFile>

    /** The current size and modification time and place of [fileIds]. */
    @Query("SELECT id, size, mtime, volume_name AS volume, relative_path AS relativePath, duration_ms AS durationMs, duration_source AS durationSource FROM audio_file WHERE id IN (:fileIds)")
    suspend fun fileStates(fileIds: List<Long>): List<FileState>

    /** The content key of a file a read has just measured, and what is stored of its length (docs/library.md §7.1). */
    @Query("UPDATE audio_file SET content_key = :key, duration_ms = :durationMs, duration_source = :source WHERE id = :fileId")
    suspend fun setContent(fileId: Long, key: String, durationMs: Long?, source: String)

    @Query("UPDATE audio_file SET duration_ms = :durationMs, duration_source = :source WHERE id = :fileId")
    suspend fun setDuration(fileId: Long, durationMs: Long?, source: String)

    /** The lengths the walks have still to settle, in the order of the tag pass (docs/library.md §7.2). */
    @Query(
        "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, f.volume_name AS volume, f.display_name AS displayName, " +
            "f.size, f.mtime, f.duration_ms AS durationMs, f.duration_source AS durationSource " +
            "FROM audio_file f LEFT JOIN track_display d ON d.file_id = f.id " +
            "WHERE f.duration_source IN ('${DurationSource.PENDING_WALK}', '${DurationSource.PENDING_FLAC_END}') " +
            "AND f.unsafe_count < :unsafeCount AND f.folder_rule != 2 " +
            "ORDER BY (f.is_ringtone OR f.is_notification OR f.is_alarm OR f.recording), d.title_key, f.id",
    )
    suspend fun pendingDurations(unsafeCount: Int): List<PendingDuration>

    @Query("UPDATE audio_file SET tag_failure = :failure, reader_version = :readerVersion WHERE id = :fileId")
    suspend fun markFailed(fileId: Long, failure: String, readerVersion: Int?)

    /** Leaves what was read in place, and has the next pass read every file again. */
    @Query("UPDATE audio_file SET reader_version = 0")
    suspend fun forgetReaderVersions()

    /** Leaves what was read in place, and has the next pass read the file again. */
    @Query("UPDATE audio_file SET reader_version = NULL WHERE id IN (:fileIds)")
    suspend fun readAgain(fileIds: List<Long>)

    @Query(
        "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, d.duration_ms AS durationMs " +
            "FROM track_display d JOIN audio_file f ON f.id = d.file_id WHERE d.file_id IN (:fileIds)",
    )
    suspend fun songRowsOf(fileIds: List<Long>): List<SongRow>

    @Query("SELECT * FROM album")
    suspend fun albums(): List<AlbumEntity>

    @Query("SELECT * FROM artist")
    suspend fun artists(): List<ArtistEntity>

    @Query("SELECT * FROM genre")
    suspend fun genres(): List<GenreEntity>

    @Query("SELECT library_id FROM library_meta WHERE id = 1")
    suspend fun storedLibraryId(): String?

    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter, library_id) VALUES (1, 0, :libraryId) " +
            "ON CONFLICT(id) DO UPDATE SET library_id = COALESCE(library_id, :libraryId)",
    )
    suspend fun keepLibraryId(libraryId: String)

    /** Gives the library [libraryId]: a rebuild keeps the one it had (docs/library.md §8.5). */
    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter, library_id) VALUES (1, 0, :libraryId) " +
            "ON CONFLICT(id) DO UPDATE SET library_id = :libraryId",
    )
    suspend fun setLibraryId(libraryId: String)

    /** The ID of the library, made the first time it is asked for (docs/library.md §8.4). */
    suspend fun libraryId(): String {
        storedLibraryId()?.let { return it }
        keepLibraryId(UUID.randomUUID().toString())
        return checkNotNull(storedLibraryId())
    }

    /** The highest file ID ever given (docs/library.md §8.4). */
    @Query("SELECT COALESCE((SELECT last_file_id FROM library_meta WHERE id = 1), 0)")
    suspend fun lastGivenFileId(): Long

    @Query(
        "INSERT INTO library_meta (id, mirrored_user_counter, last_file_id) VALUES (1, 0, :fileId) " +
            "ON CONFLICT(id) DO UPDATE SET last_file_id = MAX(last_file_id, :fileId)",
    )
    suspend fun raiseLastFileId(fileId: Long)

    /** Every file with its place: what a rebuild keeps (docs/library.md §8.5). */
    @Query(
        "SELECT id AS fileId, volume_name AS volume, relative_path AS relativePath, display_name AS displayName " +
            "FROM audio_file",
    )
    suspend fun places(): List<StoredPlace>

    /** The files whose content key is known, as the attach pass needs them (docs/library.md §8.2). */
    @Query(
        "SELECT f.id AS fileId, f.content_key AS contentKey, f.volume_name AS volume, f.relative_path AS relativePath, " +
            "f.display_name AS displayName, f.size, f.duration_ms AS durationMs, f.duration_source AS durationSource, " +
            "t.recording_mbid AS recordingMbid, f.format, t.file_id IS NOT NULL AS tagsRead FROM audio_file f " +
            "LEFT JOIN track t ON t.file_id = f.id AND t.start_ms = 0 WHERE f.content_key IS NOT NULL",
    )
    suspend fun keyedFiles(): List<KeyedFileRow>

    @Query("$FOUND_FILES WHERE f.id = :fileId")
    suspend fun foundFile(fileId: Long): FoundFileRow?

    @Query("$FOUND_FILES WHERE f.content_key = :contentKey")
    suspend fun foundFilesWithKey(contentKey: String): List<FoundFileRow>

    @Query("$FOUND_FILES WHERE f.volume_name = :volume AND f.relative_path = :relativePath AND f.display_name = :displayName")
    suspend fun foundFilesAt(volume: String, relativePath: String, displayName: String): List<FoundFileRow>

    @Query("SELECT media_store_id FROM audio_file WHERE id = :fileId")
    suspend fun mediaStoreIdOf(fileId: Long): Long?

    /** The file of a media index ID, for a saved queue of schema 2 (docs/playback.md §4.8). */
    @Query("SELECT id FROM audio_file WHERE media_store_id = :mediaStoreId ORDER BY id LIMIT 1")
    suspend fun fileIdOfMediaStoreId(mediaStoreId: Long): Long?

    @Query("SELECT * FROM fingerprint WHERE content_key = :contentKey")
    suspend fun fingerprint(contentKey: String): FingerprintEntity?

    @Upsert
    suspend fun saveFingerprint(fingerprint: FingerprintEntity)

    @Query("SELECT content_key FROM fingerprint")
    suspend fun fingerprintKeys(): List<String>

    /** Cached fingerprints older than [before] whose key no file holds; the caller keeps those of identities. */
    @Query(
        "SELECT content_key FROM fingerprint WHERE computed_at < :before " +
            "AND content_key NOT IN (SELECT content_key FROM audio_file WHERE content_key IS NOT NULL)",
    )
    suspend fun staleFingerprints(before: Long): List<String>

    @Query("DELETE FROM fingerprint WHERE content_key IN (:keys)")
    suspend fun deleteFingerprints(keys: List<String>)

    @Query("SELECT * FROM track_artist")
    suspend fun trackArtists(): List<TrackArtistEntity>

    @Query("SELECT * FROM track_genre")
    suspend fun trackGenres(): List<TrackGenreEntity>

    @Query(
        "INSERT OR IGNORE INTO album (album_key, title, album_artist, release_mbid, compilation) " +
            "VALUES (:key, :title, :albumArtist, :releaseMbid, :compilation)",
    )
    suspend fun addAlbum(key: String, title: String, albumArtist: String?, releaseMbid: String?, compilation: Boolean)

    @Query(
        "UPDATE album SET title = :title, album_artist = :albumArtist, compilation = :compilation " +
            "WHERE album_key = :key AND (title != :title OR album_artist IS NOT :albumArtist OR compilation != :compilation)",
    )
    suspend fun updateAlbum(key: String, title: String, albumArtist: String?, compilation: Boolean)

    @Query("SELECT id FROM album WHERE album_key = :key")
    suspend fun albumId(key: String): Long

    @Query("INSERT OR IGNORE INTO artist (artist_key, name, sort_name, mbid) VALUES (:key, :name, :sortName, :mbid)")
    suspend fun addArtist(key: String, name: String, sortName: String?, mbid: String?)

    @Query(
        "UPDATE artist SET name = :name, sort_name = COALESCE(:sortName, sort_name) " +
            "WHERE artist_key = :key AND (name != :name OR (:sortName IS NOT NULL AND sort_name IS NOT :sortName))",
    )
    suspend fun updateArtist(key: String, name: String, sortName: String?)

    @Query("SELECT id FROM artist WHERE artist_key = :key")
    suspend fun artistId(key: String): Long

    @Query("INSERT OR IGNORE INTO genre (genre_key, name) VALUES (:key, :name)")
    suspend fun addGenre(key: String, name: String)

    @Query("SELECT id FROM genre WHERE genre_key = :key")
    suspend fun genreId(key: String): Long

    @Insert
    suspend fun addTrackArtists(rows: List<TrackArtistEntity>)

    @Insert
    suspend fun addTrackGenres(rows: List<TrackGenreEntity>)

    @Query("DELETE FROM album WHERE id NOT IN (SELECT album_id FROM track WHERE album_id IS NOT NULL)")
    suspend fun deleteAlbumsWithoutTracks()

    @Query("DELETE FROM artist WHERE id NOT IN (SELECT artist_id FROM track_artist)")
    suspend fun deleteArtistsWithoutTracks()

    @Query("DELETE FROM genre WHERE id NOT IN (SELECT genre_id FROM track_genre)")
    suspend fun deleteGenresWithoutTracks()
}

/** A file with its ID and place, as a rebuild keeps it (docs/library.md §8.5). */
data class StoredPlace(val fileId: Long, val volume: String, val relativePath: String, val displayName: String)

/** A file with its content key, as the attach pass reads it. */
data class KeyedFileRow(
    val fileId: Long,
    val contentKey: String,
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val size: Long,
    val durationMs: Long?,
    val durationSource: String?,
    val recordingMbid: String?,
    val format: String?,
    val tagsRead: Boolean,
)

/** A file as a saved track reference is looked up: its place, key and the length lists show. */
data class FoundFileRow(
    val fileId: Long,
    val volume: String,
    val relativePath: String,
    val displayName: String,
    val contentKey: String?,
    val durationMs: Long?,
)

/** A file's size, modification time and place as the database holds them now. */
data class FileState(
    val id: Long,
    val size: Long,
    val mtime: Long,
    val volume: String,
    val relativePath: String,
    val durationMs: Long?,
    val durationSource: String?,
)

/** A read track as the display needs it; no raw tags, because a sync may read this for every file. */
data class ReadFileRow(
    val fileId: Long,
    val title: String?,
    val artist: String?,
    val artists: String?,
    val album: String?,
    val composers: String?,
    val genres: String?,
    val recordingMbid: String?,
    val size: Long,
    val mtime: Long,
    val displayName: String,
    val relativePath: String,
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
    val exactDurationMs: Long?,
)

/** A folder of a volume, as the media index gives its path. */
data class FolderKey(val volume: String, val relativePath: String)

/** A folder with a rule its files carry. */
/** A file with a length still to settle; [durationSource] says what is left to do. */
data class PendingDuration(
    val fileId: Long,
    val mediaStoreId: Long,
    val volume: String,
    val displayName: String,
    val size: Long,
    val mtime: Long,
    val durationMs: Long?,
    val durationSource: String,
) {
    val file get() = PassFile(fileId, mediaStoreId, volume, displayName, size, mtime)

    val plan
        get() = if (durationSource == DurationSource.PENDING_FLAC_END) {
            DurationPlan(durationMs, DurationCheck.FLAC_END)
        } else {
            DurationPlan(null, DurationCheck.WALK)
        }
}

data class FolderRuleRow(val volume: String, val relativePath: String, val rule: Int)

private const val FOUND_FILES = "SELECT f.id AS fileId, f.volume_name AS volume, f.relative_path AS relativePath, " +
    "f.display_name AS displayName, f.content_key AS contentKey, COALESCE(d.duration_ms, f.duration_ms) AS durationMs " +
    "FROM audio_file f LEFT JOIN track_display d ON d.file_id = f.id"

private const val READ_FILES = "SELECT t.file_id AS fileId, t.title, t.artist, t.artists, t.album, t.composers, t.genres, " +
    "t.recording_mbid AS recordingMbid, f.size, f.mtime, f.display_name AS displayName, " +
    "f.relative_path AS relativePath, " +
    "f.embedded_cover_present AS embeddedCover, f.tag_length_ms AS tagLengthMs, f.duration_ms AS exactDurationMs " +
    "FROM track t JOIN audio_file f ON f.id = t.file_id"

private const val INDEX_INPUTS = "SELECT f.id AS fileId, f.display_name AS displayName, e.title, e.artist, e.album, " +
    "e.composer, e.genre, e.duration_ms AS durationMs, f.duration_ms AS exactDurationMs " +
    "FROM audio_file f LEFT JOIN media_index_entry e ON e.file_id = f.id"

// Excluded folders never; included folders whole; elsewhere every file but the hidden kinds and short files. A file
// whose length is unknown is never short (docs/library.md §3.5).
private const val SONGS = "SELECT f.id AS fileId, f.media_store_id AS mediaStoreId, d.title, d.artist, d.album, d.duration_ms AS durationMs " +
    "FROM track_display d JOIN audio_file f ON f.id = d.file_id " +
    "WHERE f.folder_rule != 2 AND (f.folder_rule = 1 OR (" +
    "(:showSystemSounds OR NOT (f.is_ringtone OR f.is_notification OR f.is_alarm)) " +
    "AND (:showRecordings OR NOT f.recording) " +
    "AND (d.duration_ms IS NULL OR d.duration_ms >= :minLengthMs)))"
