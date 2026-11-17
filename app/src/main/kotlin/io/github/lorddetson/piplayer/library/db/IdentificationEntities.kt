package io.github.lorddetson.piplayer.library.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import androidx.room3.withWriteTransaction

// The working rows of identification (TI §10.1). They are kept by content key, not by file ID: a file opened from
// another app has none, and a library file the tag pass has not read gets its key at stage 0 (TI §3.1). The pipeline
// writes them through IdentificationRunDao.

/** One lookup job per audio content (TI §10.1), with the "retry later" time of TI §3.2. */
@Entity(tableName = "lookup_job", indices = [Index("batch_uuid")])
data class LookupJobEntity(
    @PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "batch_uuid") val batchUuid: String?,
    val phase: String,
    val status: String,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
) {
    companion object {
        // The values a single-file run (M3) writes (TI §10.1), kept apart from the batch's, which live beside
        // [BatchEntity]: a batch run writes the same phase with its own batch UUID and keeps its state in `batch`.
        const val PHASE_LOOKUP = "lookup"
        const val STATUS_RETRY_LATER = "retry_later"
        const val STATUS_FINAL = "final"
        const val ERROR_EMPTY = "acoustid_empty"
    }
}

/** A batch of phases (a) to (c) (TI §8, §10.1): what started it, when, how far it is and whether M4b writes tags. */
@Entity(tableName = "batch")
data class BatchEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val source: String,
    val status: String,
    /** Reserved for M4b (TI §8): false or null throughout M4a. */
    @ColumnInfo(name = "write_to_file") val writeToFile: Boolean? = null,
) {
    companion object {
        // What started the batch (TI §1 "Entry points").
        const val SOURCE_FOLDER = "folder"
        const val SOURCE_SELECTION = "selection"
        const val SOURCE_MISSING_INFO = "missing_info"

        // The batch's state (TI §8): made, then running phase (a) and (b) jobs, then waiting for review, then done or
        // cancelled. `done` and `cancelled` are final.
        const val STATUS_QUEUED = "queued"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_REVIEW = "review"
        const val STATUS_DONE = "done"
        const val STATUS_CANCELLED = "cancelled"
    }
}

/** A ranked candidate of the last run (TI §10.1); [position] 0 is the best. */
@Entity(tableName = "candidate", primaryKeys = ["content_key", "position"])
data class CandidateEntity(
    @ColumnInfo(name = "content_key") val contentKey: String,
    val position: Int,
    val source: String,
    @ColumnInfo(name = "recording_mbid") val recordingMbid: String?,
    @ColumnInfo(name = "release_mbid") val releaseMbid: String?,
    val score: Double,
    val reason: String?,
    @ColumnInfo(name = "payload_json") val payloadJson: String?,
)

/** One step of a run in the trace of the last 3 runs (TI §10.1), in [position] order within its run. */
@Entity(tableName = "identification_trace", primaryKeys = ["content_key", "run_id", "position"])
data class IdentificationTraceEntity(
    @ColumnInfo(name = "content_key") val contentKey: String,
    @ColumnInfo(name = "run_id") val runId: String,
    val position: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val stage: String,
    val provider: String?,
    @ColumnInfo(name = "http_status") val httpStatus: Int?,
    @ColumnInfo(name = "result_count") val resultCount: Int?,
    @ColumnInfo(name = "best_score") val bestScore: Double?,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long?,
    val note: String?,
)

/** A release lookup of TI §5.1 as MusicBrainz JSON, under one MBID it is found by (TI §10.1). */
@Entity(tableName = "release_cache", indices = [Index("fetched_at")])
data class ReleaseCacheEntity(
    @PrimaryKey @ColumnInfo(name = "release_mbid") val releaseMbid: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/**
 * An answer of an online service with its time to live (TI §10.1). In M3 only the failure entries of TI §5.2: the
 * address as [key], [status] 404 or 503, no [body], [ttl] in milliseconds from [fetchedAt], and for a 503 the 503s of
 * the address in a row.
 */
@Entity(tableName = "api_cache")
data class ApiCacheEntity(
    @PrimaryKey val key: String,
    val status: Int,
    val body: ByteArray?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    val ttl: Long,
    @ColumnInfo(name = "busy_in_a_row", defaultValue = "0") val busyInARow: Int = 0,
) {
    override fun equals(other: Any?): Boolean = other is ApiCacheEntity && key == other.key && status == other.status &&
        body.contentEquals(other.body) && fetchedAt == other.fetchedAt && ttl == other.ttl && busyInARow == other.busyInARow

    override fun hashCode(): Int = key.hashCode()
}

/**
 * A decided value of a file (TI §7.1, §10.1): the winning value of precedence level 1 or 2 for one [field] (a name of
 * the protected-fields list, TI §10), with the user database's decision it comes from. [value] may be null, as when an
 * edit clears a field. Kept beside `track_display`, whose writers replace display rows whole and join these.
 */
@Entity(tableName = "decided_mirror", primaryKeys = ["file_id", "field"])
data class DecidedMirrorEntity(
    @ColumnInfo(name = "file_id") val fileId: Long,
    val field: String,
    val value: String?,
    val level: Int,
    @ColumnInfo(name = "decision_id") val decisionId: Long,
) {
    companion object {
        /** Precedence level 1: a user edit (TI §7.1). */
        const val LEVEL_USER_EDIT = 1

        /** Precedence level 2: a confirmed identification (TI §7.1). */
        const val LEVEL_IDENTIFICATION = 2
    }
}

/** The release cache and `api_cache` of the library database (TI §5.1, §5.2, §10.1). */
@Dao
interface IdentificationCacheDao {

    @Query("SELECT * FROM release_cache WHERE release_mbid = :releaseId")
    suspend fun release(releaseId: String): ReleaseCacheEntity?

    @Upsert
    suspend fun saveReleases(rows: List<ReleaseCacheEntity>)

    @Query("DELETE FROM release_cache WHERE fetched_at < :before")
    suspend fun dropReleasesBefore(before: Long)

    @Query("DELETE FROM release_cache WHERE release_mbid = :releaseId")
    suspend fun dropRelease(releaseId: String)

    @Query("SELECT release_mbid FROM release_cache ORDER BY release_mbid")
    suspend fun releaseIds(): List<String>

    @Query("SELECT * FROM api_cache WHERE `key` = :key")
    suspend fun api(key: String): ApiCacheEntity?

    @Upsert
    suspend fun saveApi(row: ApiCacheEntity)

    @Query("DELETE FROM api_cache WHERE `key` = :key")
    suspend fun dropApi(key: String)

    /** Drops the rows that expired before [before]. */
    @Query("DELETE FROM api_cache WHERE fetched_at + ttl < :before")
    suspend fun dropApiExpiredBefore(before: Long)

    @Query("SELECT `key` FROM api_cache ORDER BY `key`")
    suspend fun apiKeys(): List<String>

    /**
     * Keeps [trackId], the AcoustID track ID of the best result of a lookup, with the fingerprint cached for [contentKey]
     * (TI §3.2); a key without a cached fingerprint keeps nothing. Computing the fingerprint again keeps it.
     */
    @Query("UPDATE fingerprint SET acoustid_id = :trackId WHERE content_key = :contentKey")
    suspend fun keepAcoustIdTrack(contentKey: String, trackId: String)
}

/** A file's size, modification time (seconds) and content key, null without one yet (TI §1). */
data class FileStampRow(val size: Long, val mtime: Long, val contentKey: String?)

/**
 * The lookup jobs, candidates and trace of identification (TI §10.1), by content key. The pipeline writes a run's rows in
 * one transaction (`RoomIdentificationStore`).
 */
@Dao
interface IdentificationRunDao {

    @Query("SELECT * FROM lookup_job WHERE content_key = :contentKey")
    suspend fun job(contentKey: String): LookupJobEntity?

    @Upsert
    suspend fun saveJob(job: LookupJobEntity)

    @Query("DELETE FROM lookup_job WHERE content_key = :contentKey")
    suspend fun dropJob(contentKey: String)

    @Query("SELECT * FROM candidate WHERE content_key = :contentKey ORDER BY position")
    suspend fun candidates(contentKey: String): List<CandidateEntity>

    @Query("DELETE FROM candidate WHERE content_key = :contentKey")
    suspend fun dropCandidates(contentKey: String)

    @Insert
    suspend fun addCandidates(rows: List<CandidateEntity>)

    /** The trace of [contentKey], run after run in the order the rows were added, each run in position order. */
    @Query("SELECT * FROM identification_trace WHERE content_key = :contentKey ORDER BY rowid")
    suspend fun trace(contentKey: String): List<IdentificationTraceEntity>

    @Insert
    suspend fun addTrace(rows: List<IdentificationTraceEntity>)

    /**
     * The runs traced for [contentKey], oldest first by the order their rows were added (row ID), not by `started_at`,
     * which a clock set back would mix up.
     */
    @Query(
        "SELECT run_id FROM identification_trace WHERE content_key = :contentKey GROUP BY run_id ORDER BY MIN(rowid)",
    )
    suspend fun tracedRuns(contentKey: String): List<String>

    @Query("DELETE FROM identification_trace WHERE content_key = :contentKey AND run_id IN (:runIds)")
    suspend fun dropTracedRuns(contentKey: String, runIds: List<String>)
}

/** The batches of phases (a) to (c) (TI §8, §10.1), by UUID. */
@Dao
interface BatchDao {

    @Query("SELECT * FROM batch WHERE uuid = :uuid")
    suspend fun batch(uuid: String): BatchEntity?

    @Upsert
    suspend fun save(batch: BatchEntity)

    @Query("UPDATE batch SET status = :status WHERE uuid = :uuid")
    suspend fun setStatus(uuid: String, status: String)

    /** The batches in [status], oldest first: those a phase's worker picks up next. */
    @Query("SELECT * FROM batch WHERE status = :status ORDER BY created_at")
    suspend fun inStatus(status: String): List<BatchEntity>

    /** Every batch, oldest first (TI §8): the order the Fix tab's queue shows. */
    @Query("SELECT * FROM batch ORDER BY created_at")
    suspend fun all(): List<BatchEntity>
}

/** A file's place and content key. */
data class FilePlaceRow(val fileId: Long, val volume: String, val relativePath: String, val displayName: String, val contentKey: String?)

/** Reads and plain writes of `decided_mirror` (TI §10.1); whole replacements go through [replaceDecided]. */
@Dao
interface DecidedMirrorDao {

    @Query("SELECT * FROM decided_mirror WHERE file_id IN (:fileIds) ORDER BY file_id, field")
    suspend fun valuesOf(fileIds: List<Long>): List<DecidedMirrorEntity>

    @Query("SELECT * FROM decided_mirror ORDER BY file_id, field")
    suspend fun all(): List<DecidedMirrorEntity>

    /** The files that hold the content keys [keys] (at most [CHUNK] at once). */
    @Query("SELECT id, content_key FROM audio_file WHERE content_key IN (:keys)")
    suspend fun filesWithKeys(keys: List<String>): List<FileKeyRow>

    /** The Keep as-is and Skip rows of [fileIds] (at most [CHUNK] at once; TI §1, §10.1). */
    @Query("SELECT * FROM decided_mirror WHERE field = 'missing_info_decision' AND file_id IN (:fileIds)")
    suspend fun missingInfoOf(fileIds: List<Long>): List<DecidedMirrorEntity>

    /**
     * The size, modification time (seconds) and content key of the files that hold one of [keys] (at most [CHUNK] at once):
     * those of Skips' tracks (TI §1).
     */
    @Query(STAMPS_WITH_KEYS)
    suspend fun stampsWithKeys(keys: List<String>): List<FileStampRow>

    /** The same of the files without a key yet that have one of [sizes] (at most [CHUNK] at once): they may hold a Skip's stamp. */
    @Query(UNKEYED_STAMPS)
    suspend fun unkeyedStamps(sizes: List<Long>): List<FileStampRow>

    /** The places and keys of [fileIds] (at most [CHUNK] at once). */
    @Query(
        "SELECT id AS fileId, volume_name AS volume, relative_path AS relativePath, display_name AS displayName, " +
            "content_key AS contentKey FROM audio_file WHERE id IN (:fileIds)",
    )
    suspend fun placesOf(fileIds: List<Long>): List<FilePlaceRow>
    /**
     * The release MBIDs of the files of [volume] in the folder [from] or one folder below it, [until] ending the range of
     * its paths (TI §4.2 key 1): each file's decided value when it has one, which may be empty, and the tags' value of a
     * read file. The folders below are filtered on the path alone, before anything is joined.
     */
    @Query(
        "SELECT f.relative_path AS relativePath, m.file_id IS NOT NULL AS decided, m.value AS decidedRelease, " +
            "t.release_mbid AS tagRelease FROM audio_file f " +
            "LEFT JOIN decided_mirror m ON m.file_id = f.id AND m.field = 'release_mbid' " +
            "LEFT JOIN track t ON t.file_id = f.id AND t.start_ms = 0 AND f.tag_pass_at IS NOT NULL " +
            "WHERE $FOLDER_AND_ONE_BELOW AND (m.file_id IS NOT NULL OR t.release_mbid IS NOT NULL)",
    )
    suspend fun folderReleases(volume: String, from: String, until: String): List<FolderReleaseRow>

    @Upsert
    suspend fun saveValues(rows: List<DecidedMirrorEntity>)

    @Query("DELETE FROM decided_mirror WHERE file_id IN (:fileIds)")
    suspend fun deleteFiles(fileIds: List<Long>)

    @Query("DELETE FROM decided_mirror")
    suspend fun clear()

    companion object {
        /** File IDs bound at a time, under SQLite's limit of arguments. */
        const val CHUNK = 500

        const val STAMPS_WITH_KEYS = "SELECT size, mtime, content_key AS contentKey FROM audio_file WHERE content_key IN (:keys)"
        const val UNKEYED_STAMPS =
            "SELECT size, mtime, content_key AS contentKey FROM audio_file WHERE content_key IS NULL AND size IN (:sizes)"

        /** The queries that look up the files holding a Skip's stamp (TI §1), both exact lookups of the index on key and size. */
        val STAMP_QUERIES = listOf(STAMPS_WITH_KEYS, UNKEYED_STAMPS)
    }
}

/**
 * The files `f` of `:volume` in the folder `:from` or one folder below it, `:until` ending the range of the folder's paths
 * (`:from` and [FolderKey.AFTER_PATHS]): a range of the index on volume and path, and the folders further below dropped
 * on the path alone, before anything is joined. SQLite numbers the parameters 1, 2 and 3 in this order.
 */
internal const val FOLDER_AND_ONE_BELOW = "f.volume_name = :volume AND f.relative_path >= :from AND f.relative_path < :until " +
    "AND instr(substr(f.relative_path, length(:from) + 1), '/') = length(f.relative_path) - length(:from)"

/** A file's release MBIDs for the folder hint of the release ranking (TI §4.2 key 1). */
data class FolderReleaseRow(
    val relativePath: String,
    /** Whether a decision decides the release MBID, then [decidedRelease] wins, also when it is empty. */
    val decided: Boolean,
    val decidedRelease: String?,
    val tagRelease: String?,
)

/**
 * Replaces the decided values of [fileIds] with [rows] in one transaction: a file without rows keeps none. Every row
 * must belong to one of [fileIds].
 */
suspend fun LibraryDatabase.replaceDecided(fileIds: Collection<Long>, rows: List<DecidedMirrorEntity>) {
    val ids = fileIds.toSet()
    require(rows.all { it.fileId in ids }) { "Decided values of a file that is not replaced" }
    withWriteTransaction {
        for (chunk in ids.chunked(DecidedMirrorDao.CHUNK)) decided().deleteFiles(chunk)
        for (chunk in rows.chunked(DecidedMirrorDao.CHUNK)) decided().saveValues(chunk)
    }
}

/** Replaces every decided value with [rows] in one transaction, as a full run of the mirror does. */
suspend fun LibraryDatabase.replaceAllDecided(rows: List<DecidedMirrorEntity>) {
    withWriteTransaction {
        decided().clear()
        for (chunk in rows.chunked(DecidedMirrorDao.CHUNK)) decided().saveValues(chunk)
    }
}
