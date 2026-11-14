package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** A playlist (docs/library.md §16.1). */
@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * An entry of a playlist at its [position] (from 0, without gaps), by its song's track identity, or by its hints alone
 * while the song has none (docs/library.md §16.1, §16.2). The content key is 32 bytes and the recording MBID 16.
 */
@Entity(tableName = "playlist_entry", indices = [Index("playlist_id", "position"), Index("identity_id")])
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    val position: Int,
    @ColumnInfo(name = "identity_id") val identityId: Long?,
    @ColumnInfo(name = "hint_volume_name") val hintVolumeName: String?,
    @ColumnInfo(name = "hint_relative_path") val hintRelativePath: String?,
    @ColumnInfo(name = "hint_display_name") val hintDisplayName: String?,
    @ColumnInfo(name = "hint_duration_ms") val hintDurationMs: Long?,
    @ColumnInfo(name = "hint_content_key") val hintContentKey: ByteArray?,
    @ColumnInfo(name = "hint_recording_mbid") val hintRecordingMbid: ByteArray?,
    @ColumnInfo(name = "hint_title") val hintTitle: String?,
    @ColumnInfo(name = "hint_artist") val hintArtist: String?,
) {
    // A data class compares arrays by reference; entries compare by their values.
    override fun equals(other: Any?): Boolean = other is PlaylistEntryEntity && id == other.id && playlistId == other.playlistId &&
        position == other.position && identityId == other.identityId && hintVolumeName == other.hintVolumeName &&
        hintRelativePath == other.hintRelativePath && hintDisplayName == other.hintDisplayName &&
        hintDurationMs == other.hintDurationMs && hintContentKey.contentEquals(other.hintContentKey) &&
        hintRecordingMbid.contentEquals(other.hintRecordingMbid) && hintTitle == other.hintTitle && hintArtist == other.hintArtist

    override fun hashCode(): Int = id.hashCode() * 31 + playlistId.hashCode()
}

/** A playlist as the list shows it, with its number of entries. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val entries: Int,
)

/** An entry with its identity's key and hinted place, as an entry is resolved (docs/library.md §16.2). */
data class PlaylistEntryRow(
    val id: Long,
    val position: Int,
    @ColumnInfo(name = "identity_id") val identityId: Long?,
    @ColumnInfo(name = "identity_key") val identityKey: ByteArray?,
    @ColumnInfo(name = "identity_volume") val identityVolume: String?,
    @ColumnInfo(name = "identity_path") val identityPath: String?,
    @ColumnInfo(name = "identity_name") val identityName: String?,
    @ColumnInfo(name = "hint_volume_name") val hintVolumeName: String?,
    @ColumnInfo(name = "hint_relative_path") val hintRelativePath: String?,
    @ColumnInfo(name = "hint_display_name") val hintDisplayName: String?,
    @ColumnInfo(name = "hint_duration_ms") val hintDurationMs: Long?,
    @ColumnInfo(name = "hint_content_key") val hintContentKey: ByteArray?,
    @ColumnInfo(name = "hint_recording_mbid") val hintRecordingMbid: ByteArray?,
    @ColumnInfo(name = "hint_title") val hintTitle: String?,
    @ColumnInfo(name = "hint_artist") val hintArtist: String?,
) {
    override fun equals(other: Any?): Boolean = other is PlaylistEntryRow && id == other.id && position == other.position &&
        identityId == other.identityId && identityKey.contentEquals(other.identityKey) && identityVolume == other.identityVolume &&
        identityPath == other.identityPath && identityName == other.identityName && hintVolumeName == other.hintVolumeName &&
        hintRelativePath == other.hintRelativePath && hintDisplayName == other.hintDisplayName &&
        hintDurationMs == other.hintDurationMs && hintContentKey.contentEquals(other.hintContentKey) &&
        hintRecordingMbid.contentEquals(other.hintRecordingMbid) && hintTitle == other.hintTitle && hintArtist == other.hintArtist

    override fun hashCode(): Int = id.hashCode()
}

/** The hints of an entry with an identity, for step 5 of re-attachment (docs/library.md §16.2). */
data class IdentityHintRow(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "hint_volume_name") val hintVolumeName: String?,
    @ColumnInfo(name = "hint_relative_path") val hintRelativePath: String?,
    @ColumnInfo(name = "hint_display_name") val hintDisplayName: String?,
    @ColumnInfo(name = "hint_duration_ms") val hintDurationMs: Long?,
    @ColumnInfo(name = "hint_recording_mbid") val hintRecordingMbid: ByteArray?,
)

private const val ENTRY_ROW =
    "SELECT e.id, e.position, e.identity_id, i.content_key AS identity_key, i.hint_volume_name AS identity_volume, " +
        "i.hint_relative_path AS identity_path, i.hint_display_name AS identity_name, e.hint_volume_name, e.hint_relative_path, " +
        "e.hint_display_name, e.hint_duration_ms, e.hint_content_key, e.hint_recording_mbid, e.hint_title, e.hint_artist " +
        "FROM playlist_entry e LEFT JOIN track_identity i ON i.id = e.identity_id"

private const val SUMMARY =
    "SELECT p.id, p.name, p.created_at, p.updated_at, (SELECT COUNT(*) FROM playlist_entry e WHERE e.playlist_id = p.id) AS entries " +
        "FROM playlist p"

@Dao
interface PlaylistDao {

    @Query("$SUMMARY ORDER BY p.id")
    suspend fun playlists(): List<PlaylistSummary>

    @Query("$SUMMARY ORDER BY p.id")
    fun playlistsFlow(): Flow<List<PlaylistSummary>>

    @Query("$SUMMARY WHERE p.id = :id")
    suspend fun playlist(id: Long): PlaylistSummary?

    @Query("$SUMMARY WHERE p.id = :id")
    fun playlistFlow(id: Long): Flow<PlaylistSummary?>

    @Insert
    suspend fun addPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long): Int

    @Query("UPDATE playlist SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: Long): Int

    @Query("DELETE FROM playlist_entry WHERE playlist_id = :playlistId")
    suspend fun deleteEntriesOf(playlistId: Long)

    @Query("$ENTRY_ROW WHERE e.playlist_id = :playlistId ORDER BY e.position")
    suspend fun entryRows(playlistId: Long): List<PlaylistEntryRow>

    @Query("$ENTRY_ROW WHERE e.playlist_id = :playlistId ORDER BY e.position")
    fun entryRowsFlow(playlistId: Long): Flow<List<PlaylistEntryRow>>

    @Query("SELECT * FROM playlist_entry WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entry WHERE id = :id")
    suspend fun entry(id: Long): PlaylistEntryEntity?

    @Query("SELECT COUNT(*) FROM playlist_entry WHERE playlist_id = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Insert
    suspend fun addEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    /** Moves the entries of [playlistId] from position [from] up to, not including, [to] by [by]. */
    @Query("UPDATE playlist_entry SET position = position + :by WHERE playlist_id = :playlistId AND position >= :from AND position < :to")
    suspend fun shift(playlistId: Long, from: Int, to: Int, by: Int)

    @Query("UPDATE playlist_entry SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    /** The entries of every playlist that have no identity yet, for linking (docs/library.md §16.2). */
    @Query("SELECT * FROM playlist_entry WHERE identity_id IS NULL")
    suspend fun unlinkedEntries(): List<PlaylistEntryEntity>

    @Query("UPDATE playlist_entry SET identity_id = :identityId WHERE id = :id AND identity_id IS NULL")
    suspend fun link(id: Long, identityId: Long)

    /** The distinct hints of entries that have an identity, for step 5 of re-attachment. */
    @Query(
        "SELECT DISTINCT identity_id, hint_volume_name, hint_relative_path, hint_display_name, hint_duration_ms, hint_recording_mbid " +
            "FROM playlist_entry WHERE identity_id IS NOT NULL",
    )
    suspend fun identityHints(): List<IdentityHintRow>

    @Query("DELETE FROM playlist_entry")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM playlist")
    suspend fun deleteAllPlaylists()
}
