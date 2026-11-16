package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * A counted play of the last 90 days (docs/library.md §15.5, docs/playback.md §11). Since version 6 keyed by its start
 * first and without row IDs, so that rows go in at the end and the fold takes them from the front without an index of
 * their own (§15.7).
 */
@Entity(tableName = "play_event", primaryKeys = ["started_at", "identity_id"], withoutRowId = true)
data class PlayEventEntity(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    /** When the playback started, by the wall clock. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
)

/**
 * The plays older than 90 days, folded (docs/library.md §15.5), and since version 6 the listenings as old, folded by
 * their outcome (§15.7). A row whose [playCount] is 0 holds only listening counts and is no play.
 */
@Entity(tableName = "play_stats")
data class PlayStatsEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "play_count") val playCount: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    @ColumnInfo(name = "early_skips", defaultValue = "0") val earlySkips: Long = 0,
    @ColumnInfo(name = "late_skips", defaultValue = "0") val lateSkips: Long = 0,
    @ColumnInfo(name = "finished", defaultValue = "0") val finished: Long = 0,
)

/** What the library database mirrors of an identity's plays, with its content key. */
data class PlayedKeyRow(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
    @ColumnInfo(name = "play_count") val playCount: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
)

/** An identity's plays all told: its count and its latest start. */
data class PlayTotalRow(
    @ColumnInfo(name = "play_count") val playCount: Long?,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
)

@Dao
interface PlayDao {

    /** The plays of [identityId]: its folded count plus its events, and the latest start of either (docs/library.md §18.2). */
    @Query(
        "SELECT SUM(plays) AS play_count, MAX(latest) AS last_played_at FROM (" +
            "SELECT play_count AS plays, last_played_at AS latest FROM play_stats WHERE identity_id = :identityId AND play_count > 0 " +
            "UNION ALL SELECT COUNT(*) AS plays, MAX(started_at) AS latest FROM play_event WHERE identity_id = :identityId)",
    )
    suspend fun totalOf(identityId: Long): PlayTotalRow?

    @Query("INSERT OR IGNORE INTO play_event (identity_id, started_at, listened_ms) VALUES (:identityId, :startedAt, :listenedMs)")
    suspend fun addEvent(identityId: Long, startedAt: Long, listenedMs: Long)

    @Query("UPDATE play_event SET listened_ms = :listenedMs WHERE identity_id = :identityId AND started_at = :startedAt")
    suspend fun setListened(identityId: Long, startedAt: Long, listenedMs: Long)

    @Query("SELECT * FROM play_event ORDER BY started_at, identity_id")
    suspend fun events(): List<PlayEventEntity>

    @Query("SELECT * FROM play_stats ORDER BY identity_id")
    suspend fun stats(): List<PlayStatsEntity>

    /**
     * Each identity's plays: its folded count plus its events, and the latest start of either. A row of only listening
     * counts is no play (docs/library.md §15.7).
     */
    @Query(
        "SELECT p.identity_id, i.content_key, SUM(p.plays) AS play_count, MAX(p.latest) AS last_played_at FROM (" +
            "SELECT identity_id, play_count AS plays, last_played_at AS latest FROM play_stats WHERE play_count > 0 " +
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
