package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
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

    /**
     * Folds the events that started before [before] into the listening counts of `play_stats` (docs/library.md §15.7):
     * an identity without a row gets one with a play count of 0. The caller deletes the events afterwards.
     */
    @Query(
        "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at, early_skips, late_skips, finished) " +
            "SELECT identity_id, 0, 0, NULL, SUM(outcome = 1), SUM(outcome = 2), SUM(outcome = 3) " +
            "FROM listen_event WHERE started_at < :before GROUP BY identity_id " +
            "ON CONFLICT(identity_id) DO UPDATE SET early_skips = early_skips + excluded.early_skips, " +
            "late_skips = late_skips + excluded.late_skips, finished = finished + excluded.finished",
    )
    suspend fun foldInto(before: Long)

    @Query("DELETE FROM listen_event WHERE started_at < :before")
    suspend fun deleteEventsBefore(before: Long)

    /**
     * The events of [from] become [to]'s, as when a takeover deletes [from] (docs/library.md §8.2, §15.7); one whose start
     * [to] has already stays behind, for [deleteEventsOf].
     */
    @Query("UPDATE OR IGNORE listen_event SET identity_id = :to WHERE identity_id = :from")
    suspend fun moveEvents(from: Long, to: Long)

    /** The folded listening counts of [from] are added to [to]'s; [from]'s row is one without a play (§15.7). */
    @Query(
        "INSERT INTO play_stats (identity_id, play_count, listened_ms, last_played_at, early_skips, late_skips, finished) " +
            "SELECT :to, 0, 0, NULL, early_skips, late_skips, finished FROM play_stats WHERE identity_id = :from " +
            "ON CONFLICT(identity_id) DO UPDATE SET early_skips = early_skips + excluded.early_skips, " +
            "late_skips = late_skips + excluded.late_skips, finished = finished + excluded.finished",
    )
    suspend fun addCounts(from: Long, to: Long)

    @Query("DELETE FROM listen_event WHERE identity_id IN (:identityIds)")
    suspend fun deleteEventsOf(identityIds: List<Long>)

    /** The listening counts of [identityIds]; a row with a play keeps its identity and is never one of theirs (§15.7). */
    @Query("DELETE FROM play_stats WHERE identity_id IN (:identityIds) AND play_count = 0")
    suspend fun deleteCountsOf(identityIds: List<Long>)
}
