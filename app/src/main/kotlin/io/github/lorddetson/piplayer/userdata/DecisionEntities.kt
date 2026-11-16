package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert

/**
 * One decision of a track (TI §7.1, §10.2), with the provenance of its values once per decision. The encodings of its
 * columns are those of TI §10.2: [kind] a `DecisionKind` code, [fields] the bits of the protected-fields list, the MBIDs
 * and the AcoustID track ID 16 bytes each, [confidence] in thousandths, [reason] the blob of `DecisionCodec`,
 * [fileValues] the "changed in the file" references of the location it was made on (`FileReferences`).
 */
@Entity(tableName = "decision", indices = [Index("identity_id")])
data class DecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "batch_uuid") val batchUuid: ByteArray? = null,
    val kind: Int,
    val fields: Int,
    @ColumnInfo(name = "release_choice_id") val releaseChoiceId: Long? = null,
    @ColumnInfo(name = "recording_mbid") val recordingMbid: ByteArray? = null,
    @ColumnInfo(name = "release_track_mbid") val releaseTrackMbid: ByteArray? = null,
    @ColumnInfo(name = "acoustid_id") val acoustIdId: ByteArray? = null,
    val confidence: Int? = null,
    val reason: ByteArray? = null,
    val provider: Int? = null,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long? = null,
    @ColumnInfo(name = "license_class") val licenseClass: Int? = null,
    @ColumnInfo(name = "file_size") val fileSize: Long? = null,
    @ColumnInfo(name = "file_mtime") val fileMtime: Long? = null,
    @ColumnInfo(name = "file_values") val fileValues: ByteArray? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "undone_at") val undoneAt: Long? = null,
    @ColumnInfo(name = "lapsed_at") val lapsedAt: Long? = null,
) {
    // A data class compares arrays by reference; decisions compare by their values.
    override fun equals(other: Any?): Boolean = other is DecisionEntity && id == other.id && identityId == other.identityId &&
        batchUuid.contentEquals(other.batchUuid) && kind == other.kind && fields == other.fields &&
        releaseChoiceId == other.releaseChoiceId && recordingMbid.contentEquals(other.recordingMbid) &&
        releaseTrackMbid.contentEquals(other.releaseTrackMbid) && acoustIdId.contentEquals(other.acoustIdId) &&
        confidence == other.confidence && reason.contentEquals(other.reason) && provider == other.provider && fetchedAt == other.fetchedAt &&
        licenseClass == other.licenseClass && fileSize == other.fileSize && fileMtime == other.fileMtime &&
        fileValues.contentEquals(other.fileValues) && createdAt == other.createdAt && undoneAt == other.undoneAt &&
        lapsedAt == other.lapsedAt

    override fun hashCode(): Int = id.hashCode()
}

/**
 * The release-level values of a chosen release (TI §10.2), shared by the decisions that chose it with the same values:
 * [values] is `ReleaseValues` as gzip-compressed JSON, the MBIDs 16 bytes each.
 */
@Entity(tableName = "release_choice", indices = [Index("release_mbid")])
data class ReleaseChoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "release_mbid") val releaseMbid: ByteArray,
    @ColumnInfo(name = "release_group_mbid") val releaseGroupMbid: ByteArray?,
    val values: ByteArray,
    @ColumnInfo(name = "saved_cover_id") val savedCoverId: Long?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is ReleaseChoiceEntity && id == other.id &&
        releaseMbid.contentEquals(other.releaseMbid) && releaseGroupMbid.contentEquals(other.releaseGroupMbid) &&
        values.contentEquals(other.values) && savedCoverId == other.savedCoverId && fetchedAt == other.fetchedAt

    override fun hashCode(): Int = id.hashCode()
}

/** A per-track value of a decision (TI §10.2): [field] a `DecidedField` code, [value] its stored form, null when empty. */
@Entity(tableName = "track_metadata", primaryKeys = ["decision_id", "field"], withoutRowId = true)
data class TrackMetadataEntity(
    @ColumnInfo(name = "decision_id") val decisionId: Long,
    val field: Int,
    val value: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is TrackMetadataEntity && decisionId == other.decisionId && field == other.field && value.contentEquals(other.value)

    override fun hashCode(): Int = decisionId.hashCode() * 31 + field
}

/**
 * The "changed in the file" state of one part at one location of a track for the decision [decisionId] (TI §7.1,
 * §10.2): its reference ([valueAtDecision], a 4-byte hash) when it differs from the decision's, and whether the part
 * [changedInFile]. Only the rows of the decision that wins a part count, so no decision deletes another's.
 */
@Entity(
    tableName = "file_state",
    primaryKeys = ["identity_id", "decision_id", "volume_name", "relative_path", "display_name", "field"],
    withoutRowId = true,
)
data class FileStateEntity(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "decision_id") val decisionId: Long,
    @ColumnInfo(name = "volume_name") val volumeName: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val field: Int,
    @ColumnInfo(name = "value_at_decision") val valueAtDecision: ByteArray?,
    @ColumnInfo(name = "changed_in_file") val changedInFile: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is FileStateEntity && identityId == other.identityId &&
        decisionId == other.decisionId && volumeName == other.volumeName && relativePath == other.relativePath &&
        displayName == other.displayName &&
        field == other.field && valueAtDecision.contentEquals(other.valueAtDecision) && changedInFile == other.changedInFile

    override fun hashCode(): Int = ((identityId.hashCode() * 31 + decisionId.hashCode()) * 31 + displayName.hashCode()) * 31 + field
}

/** An accepted cover (TI §5.2, §10.2): the image file lives in no-backup app storage at [path]. */
@Entity(tableName = "saved_cover")
data class SavedCoverEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "release_mbid") val releaseMbid: ByteArray?,
    val path: String,
    val width: Int?,
    val height: Int?,
    val bytes: Long?,
    val hash: String?,
    val source: String?,
) {
    override fun equals(other: Any?): Boolean = other is SavedCoverEntity && id == other.id &&
        releaseMbid.contentEquals(other.releaseMbid) && path == other.path && width == other.width && height == other.height &&
        bytes == other.bytes && hash == other.hash && source == other.source

    override fun hashCode(): Int = id.hashCode()
}

/** A decision's identity with its content key, for the mirror. */
data class DecisionKeyRow(
    @ColumnInfo(name = "id") val identityId: Long,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
)

/** Reads and plain writes of the decisions (TI §10.2); the repository is `Decisions`. */
@Dao
interface DecisionDao {

    @Insert
    suspend fun add(decision: DecisionEntity): Long

    @Insert
    suspend fun addMetadata(rows: List<TrackMetadataEntity>)

    @Query("SELECT * FROM decision WHERE id = :id")
    suspend fun decision(id: Long): DecisionEntity?

    /** Marks [id] undone at [at]; 0 when it was undone already or does not exist. */
    @Query("UPDATE decision SET undone_at = :at WHERE id = :id AND undone_at IS NULL")
    suspend fun markUndone(id: Long, at: Long): Int

    /** Every decision of [identityId], undone ones too, oldest first: by row ID, the order of the commits (TI §7.1). */
    @Query("SELECT * FROM decision WHERE identity_id = :identityId ORDER BY id")
    suspend fun decisionsOf(identityId: Long): List<DecisionEntity>

    /** Whether any decision may decide values, so that the mirror of a library without one costs nothing. */
    @Query("SELECT EXISTS(SELECT 1 FROM decision WHERE undone_at IS NULL AND kind IN (1, 2, 3))")
    suspend fun hasLiveValueDecisions(): Boolean

    /** The decisions that may decide values (apply, edit and revert_field) and are not undone. */
    @Query("SELECT * FROM decision WHERE undone_at IS NULL AND kind IN (1, 2, 3)")
    suspend fun liveValueDecisions(): List<DecisionEntity>

    /** [liveValueDecisions] of [identityIds] (at most 500 at once). */
    @Query("SELECT * FROM decision WHERE undone_at IS NULL AND kind IN (1, 2, 3) AND identity_id IN (:identityIds)")
    suspend fun liveValueDecisionsOf(identityIds: List<Long>): List<DecisionEntity>

    /** The per-track values of the decisions [liveValueDecisions] reads. */
    @Query(
        "SELECT m.* FROM track_metadata m JOIN decision d ON d.id = m.decision_id WHERE d.undone_at IS NULL AND d.kind IN (1, 2, 3)",
    )
    suspend fun liveMetadata(): List<TrackMetadataEntity>

    /** The per-track values of [decisionIds] (at most 500 at once). */
    @Query("SELECT * FROM track_metadata WHERE decision_id IN (:decisionIds)")
    suspend fun metadataOf(decisionIds: List<Long>): List<TrackMetadataEntity>

    @Insert
    suspend fun addReleaseChoice(choice: ReleaseChoiceEntity): Long

    @Query("UPDATE release_choice SET `values` = :values WHERE id = :id")
    suspend fun setReleaseValues(id: Long, values: ByteArray)

    @Query("SELECT * FROM release_choice WHERE release_mbid = :releaseMbid ORDER BY id")
    suspend fun releaseChoicesOf(releaseMbid: ByteArray): List<ReleaseChoiceEntity>

    /** The release choices [ids] (at most 500 at once). */
    @Query("SELECT * FROM release_choice WHERE id IN (:ids)")
    suspend fun releaseChoices(ids: List<Long>): List<ReleaseChoiceEntity>

    @Query("SELECT COUNT(*) FROM release_choice")
    suspend fun releaseChoiceCount(): Int

    /** The identities of [ids] (at most 500 at once) with their keys. */
    @Query("SELECT id, content_key FROM track_identity WHERE start_ms = 0 AND id IN (:ids)")
    suspend fun keysOf(ids: List<Long>): List<DecisionKeyRow>

    /** The identities that have decisions that may decide values, with their keys. */
    @Query(
        "SELECT id, content_key FROM track_identity WHERE start_ms = 0 AND id IN " +
            "(SELECT identity_id FROM decision WHERE undone_at IS NULL AND kind IN (1, 2, 3))",
    )
    suspend fun decidedKeys(): List<DecisionKeyRow>

    /**
     * Whether any decision is mirrored (TI §10.1): one that may decide values, or a Keep as-is or Skip that counts, not
     * undone; so that the mirror after a batch of the tag pass costs nothing in a library without one.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM decision WHERE undone_at IS NULL AND (kind IN (1, 2, 3) OR (kind IN (4, 5) AND lapsed_at IS NULL)))",
    )
    suspend fun hasMirroredDecisions(): Boolean

    /** The Keep as-is and Skip decisions that are neither undone nor lapsed, with their identities' keys (TI §1). */
    @Query("$LIVE_STATES WHERE d.undone_at IS NULL AND d.lapsed_at IS NULL AND d.kind IN (4, 5)")
    suspend fun liveStates(): List<StateDecisionRow>

    /** [liveStates] of [identityIds] (at most 500 at once). */
    @Query("$LIVE_STATES WHERE d.undone_at IS NULL AND d.lapsed_at IS NULL AND d.kind IN (4, 5) AND d.identity_id IN (:identityIds)")
    suspend fun liveStatesOf(identityIds: List<Long>): List<StateDecisionRow>

    @Query("SELECT EXISTS(SELECT 1 FROM decision WHERE kind = 5 AND undone_at IS NULL AND lapsed_at IS NULL)")
    suspend fun hasLiveSkips(): Boolean

    /** Every Skip that counts, with its identity's key and location hint (TI §1). */
    @Query(
        "SELECT d.id AS decisionId, d.identity_id AS identityId, i.content_key AS contentKey, i.hint_volume_name AS hintVolume, " +
            "i.hint_relative_path AS hintRelativePath, i.hint_display_name AS hintDisplayName, d.file_size AS size, " +
            "d.file_mtime AS mtimeMs FROM decision d JOIN track_identity i ON i.id = d.identity_id " +
            "WHERE d.kind = 5 AND d.undone_at IS NULL AND d.lapsed_at IS NULL AND i.start_ms = 0",
    )
    suspend fun liveSkips(): List<LiveSkipRow>

    /** Marks the Skips [ids] lapsed at [at], those that count only; how many it marked (at most 500 at once). */
    @Query("UPDATE decision SET lapsed_at = :at WHERE kind = 5 AND undone_at IS NULL AND lapsed_at IS NULL AND id IN (:ids)")
    suspend fun markLapsed(ids: List<Long>, at: Long): Int

    /** Which of [identityIds] (at most 500 at once) have decisions that may decide values and are not undone. */
    @Query("SELECT DISTINCT identity_id FROM decision WHERE undone_at IS NULL AND kind IN (1, 2, 3) AND identity_id IN (:identityIds)")
    suspend fun identitiesWithLiveValues(identityIds: List<Long>): List<Long>

    @Query("SELECT * FROM file_state WHERE identity_id = :identityId")
    suspend fun fileStates(identityId: Long): List<FileStateEntity>

    @Upsert
    suspend fun saveFileStates(rows: List<FileStateEntity>)

    @Insert
    suspend fun addSavedCover(cover: SavedCoverEntity): Long

    @Query("SELECT * FROM saved_cover WHERE id = :id")
    suspend fun savedCover(id: Long): SavedCoverEntity?

    companion object {
        /** The Keep as-is and Skip decisions with their identities' keys; the conditions follow. */
        const val LIVE_STATES = "SELECT d.id, d.identity_id AS identityId, d.kind, i.content_key AS contentKey FROM decision d " +
            "JOIN track_identity i ON i.id = d.identity_id AND i.start_ms = 0"
    }
}

/** A Keep as-is or Skip decision of [identityId] with its identity's [contentKey]. */
data class StateDecisionRow(val id: Long, val identityId: Long, val kind: Int, val contentKey: ByteArray)

/** A Skip that counts, with its identity's key and location hint and the stamp it stored. */
data class LiveSkipRow(
    val decisionId: Long,
    val identityId: Long,
    val contentKey: ByteArray,
    val hintVolume: String?,
    val hintRelativePath: String?,
    val hintDisplayName: String?,
    val size: Long?,
    val mtimeMs: Long?,
)
