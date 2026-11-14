package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query

/** A counted play of the last 90 days (docs/library.md §15.5, docs/playback.md §11). */
@Entity(tableName = "play_event", primaryKeys = ["identity_id", "started_at"], indices = [Index("started_at")])
data class PlayEventEntity(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    /** When the playback started, by the wall clock. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

/** The plays older than 90 days, folded (docs/library.md §15.5). */
@Entity(tableName = "play_stats")
data class PlayStatsEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "play_count") val playCount: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
)

/** What the library database mirrors of an identity's plays, with its content key. */
data class PlayedKeyRow(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
    @ColumnInfo(name = "play_count") val playCount: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
)

@Dao
interface PlayDao {

    @Query("INSERT OR IGNORE INTO play_event (identity_id, started_at, listened_ms) VALUES (:identityId, :startedAt, :listenedMs)")
    suspend fun addEvent(identityId: Long, startedAt: Long, listenedMs: Long)

    @Query("UPDATE play_event SET listened_ms = :listenedMs WHERE identity_id = :identityId AND started_at = :startedAt")
    suspend fun setListened(identityId: Long, startedAt: Long, listenedMs: Long)

    @Query("SELECT * FROM play_event ORDER BY started_at, identity_id")
    suspend fun events(): List<PlayEventEntity>

    @Query("SELECT * FROM play_stats ORDER BY identity_id")
    suspend fun stats(): List<PlayStatsEntity>

    /** Each identity's plays: its folded count plus its events, and the latest start of either. */
    @Query(
        "SELECT p.identity_id, i.content_key, SUM(p.plays) AS play_count, MAX(p.latest) AS last_played_at FROM (" +
            "SELECT identity_id, play_count AS plays, last_played_at AS latest FROM play_stats " +
            "UNION ALL SELECT identity_id, COUNT(*) AS plays, MAX(started_at) AS latest FROM play_event GROUP BY identity_id" +
            ") p JOIN track_identity i ON i.id = p.identity_id GROUP BY p.identity_id",
    )
    suspend fun playedKeys(): List<PlayedKeyRow>

    /** Folds the events that started before [before] into `play_stats`; the caller deletes them afterwards. */
    @Query(
        "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at) " +
            "SELECT identity_id, COUNT(*), SUM(listened_ms), MAX(started_at) FROM play_event WHERE started_at < :before " +
            "GROUP BY identity_id " +
            "ON CONFLICT(identity_id) DO UPDATE SET play_count = play_count + excluded.play_count, " +
            "listened_ms = listened_ms + excluded.listened_ms, " +
            "last_played_at = MAX(COALESCE(last_played_at, 0), excluded.last_played_at)",
    )
    suspend fun foldInto(before: Long)

    @Query("DELETE FROM play_event WHERE started_at < :before")
    suspend fun deleteEventsBefore(before: Long)
}
