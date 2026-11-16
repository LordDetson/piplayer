package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query

/** How a listening ended (docs/playback.md §12.2), by the code `listen_event.outcome` stores (docs/library.md §15.7). */
enum class ListenOutcome(val code: Int) {
    /** Moved on before the end after less than 30 s of listened time. */
    EARLY_SKIP(1),

    /** Moved on before the end after 30 s of listened time or more. */
    LATE_SKIP(2),

    /** Played into the end of the song. */
    FINISHED(3),
    ;

    companion object {
        /** The outcome of [code], or null for a code this build does not know. */
        fun of(code: Int): ListenOutcome? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A listening of the last 90 days that ended with an outcome (docs/library.md §15.7). Keyed by its start first, so that
 * rows go in at the end of the table and the fold takes them from its front; without row IDs and without another index.
 */
@Entity(tableName = "listen_event", primaryKeys = ["started_at", "identity_id"], withoutRowId = true)
data class ListenEventEntity(
    /** When the playback started, by the wall clock. */
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    /** The song's length, or null when it was not known. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    /** A [ListenOutcome.code]. */
    val outcome: Int,
)

/** The listenings older than 90 days, folded per identity (docs/library.md §15.7). */
@Entity(tableName = "listen_stats")
data class ListenStatsEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "early_skips") val earlySkips: Long,
    @ColumnInfo(name = "late_skips") val lateSkips: Long,
    val finished: Long,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "last_started_at") val lastStartedAt: Long?,
)

@Dao
interface ListenDao {

    /** A listening's outcome; a second one for the same start and identity is ignored, so a playback keeps its first. */
    @Query(
        "INSERT OR IGNORE INTO listen_event (started_at, identity_id, listened_ms, duration_ms, outcome) " +
            "VALUES (:startedAt, :identityId, :listenedMs, :durationMs, :outcome)",
    )
    suspend fun add(startedAt: Long, identityId: Long, listenedMs: Long, durationMs: Long?, outcome: Int)

    @Query("SELECT * FROM listen_event ORDER BY started_at, identity_id")
    suspend fun events(): List<ListenEventEntity>

    @Query("SELECT * FROM listen_stats ORDER BY identity_id")
    suspend fun stats(): List<ListenStatsEntity>

    /** Folds the events that started before [before] into `listen_stats`; the caller deletes them afterwards. */
    @Query(
        "INSERT INTO listen_stats (identity_id, early_skips, late_skips, finished, listened_ms, last_started_at) " +
            "SELECT identity_id, SUM(outcome = 1), SUM(outcome = 2), SUM(outcome = 3), SUM(listened_ms), MAX(started_at) " +
            "FROM listen_event WHERE started_at < :before GROUP BY identity_id " +
            "ON CONFLICT(identity_id) DO UPDATE SET early_skips = early_skips + excluded.early_skips, " +
            "late_skips = late_skips + excluded.late_skips, finished = finished + excluded.finished, " +
            "listened_ms = listened_ms + excluded.listened_ms, " +
            "last_started_at = MAX(COALESCE(last_started_at, 0), excluded.last_started_at)",
    )
    suspend fun foldInto(before: Long)

    @Query("DELETE FROM listen_event WHERE started_at < :before")
    suspend fun deleteEventsBefore(before: Long)
}
