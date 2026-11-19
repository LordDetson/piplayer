package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import kotlinx.coroutines.flow.Flow

/** This preference follows the identity even when the library is rebuilt. */
@Entity(
    tableName = "track_legacy_encoding",
    foreignKeys = [ForeignKey(
        entity = TrackIdentityEntity::class,
        parentColumns = ["id"],
        childColumns = ["identity_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TrackLegacyEncoding(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    val name: String?,
    val pending: Boolean,
) {
    companion object {
        /** A reset stays in the database until the library has applied it. Null means per-track Automatic. */
        const val FOLLOW_LIBRARY = ""
    }
}

data class PendingTrackLegacyEncoding(
    @Embedded val choice: TrackLegacyEncoding,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
)

@Dao
interface TrackLegacyEncodingDao {
    @Query("SELECT * FROM track_legacy_encoding WHERE identity_id = :identityId")
    suspend fun choice(identityId: Long): TrackLegacyEncoding?

    @Query(
        "SELECT e.* FROM track_legacy_encoding e JOIN track_identity i ON i.id = e.identity_id " +
            "WHERE i.content_key = :key AND i.start_ms = 0",
    )
    suspend fun choiceForKey(key: ByteArray): TrackLegacyEncoding?

    @Query(
        "SELECT e.*, i.content_key FROM track_legacy_encoding e JOIN track_identity i ON i.id = e.identity_id " +
            "WHERE e.pending AND i.start_ms = 0",
    )
    suspend fun pending(): List<PendingTrackLegacyEncoding>

    @Query("SELECT * FROM track_legacy_encoding WHERE pending ORDER BY identity_id")
    fun pendingChanges(): Flow<List<TrackLegacyEncoding>>

    @Upsert
    suspend fun save(choice: TrackLegacyEncoding)

    @Query(
        "UPDATE track_legacy_encoding SET pending = 1 WHERE identity_id IN (:ids) AND (name IS NULL OR name <> '')",
    )
    suspend fun markPending(ids: List<Long>)

    @Query(
        "UPDATE track_legacy_encoding SET pending = 0 WHERE identity_id = :identityId AND name IS :name " +
            "AND EXISTS (SELECT 1 FROM track_identity WHERE id = :identityId AND content_key = :key)",
    )
    suspend fun applied(identityId: Long, name: String?, key: ByteArray)

    @Query(
        "DELETE FROM track_legacy_encoding WHERE identity_id = :identityId AND name = '' " +
            "AND EXISTS (SELECT 1 FROM track_identity WHERE id = :identityId AND content_key = :key)",
    )
    suspend fun clearReset(identityId: Long, key: ByteArray)
}

internal fun TrackLegacyEncoding?.followsLibrary(): Boolean = this == null || name == TrackLegacyEncoding.FOLLOW_LIBRARY

internal fun TrackLegacyEncoding?.overrideEncoding(): LegacyEncoding? =
    LegacyEncodings.fromStored(this?.name)

internal fun TrackLegacyEncoding?.decode(
    properties: Map<String, List<String>>,
    cyrillic: Boolean,
    global: LegacyEncoding?,
): LegacyText.Decoded =
    if (followsLibrary()) {
        LegacyText.decode(properties, cyrillic, global)
    } else {
        LegacyText.decode(properties, cyrillic, overrideEncoding(), preferUtf8 = false)
    }
