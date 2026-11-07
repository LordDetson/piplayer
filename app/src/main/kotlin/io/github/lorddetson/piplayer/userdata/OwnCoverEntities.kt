package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query

/** A track's own cover (docs/library.md §13.2): the copy of the chosen image, by the track's identity. */
@Entity(tableName = "track_cover")
data class TrackCoverEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    /** The copy's name in the covers folder of the no-backup files. */
    @ColumnInfo(name = "file_name") val fileName: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "set_at") val setAt: Long,
)

/** An album's own cover (docs/library.md §13.2), by the album key (§6.2). */
@Entity(tableName = "album_cover")
data class AlbumCoverEntity(
    @PrimaryKey @ColumnInfo(name = "album_key") val albumKey: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "set_at") val setAt: Long,
)

@Dao
interface OwnCoverDao {

    @Query("SELECT * FROM track_cover")
    suspend fun trackCovers(): List<TrackCoverEntity>

    @Query("SELECT * FROM album_cover")
    suspend fun albumCovers(): List<AlbumCoverEntity>

    @Query("SELECT * FROM track_cover WHERE identity_id = :identityId")
    suspend fun trackCover(identityId: Long): TrackCoverEntity?

    @Query("SELECT * FROM album_cover WHERE album_key = :albumKey")
    suspend fun albumCover(albumKey: String): AlbumCoverEntity?

    @Query(
        "INSERT OR REPLACE INTO track_cover (identity_id, file_name, width, height, set_at) " +
            "VALUES (:identityId, :fileName, :width, :height, :setAt)",
    )
    suspend fun putTrackCover(identityId: Long, fileName: String, width: Int, height: Int, setAt: Long)

    @Query(
        "INSERT OR REPLACE INTO album_cover (album_key, file_name, width, height, set_at) " +
            "VALUES (:albumKey, :fileName, :width, :height, :setAt)",
    )
    suspend fun putAlbumCover(albumKey: String, fileName: String, width: Int, height: Int, setAt: Long)

    @Query("DELETE FROM track_cover WHERE identity_id = :identityId")
    suspend fun removeTrackCover(identityId: Long)

    @Query("DELETE FROM album_cover WHERE album_key = :albumKey")
    suspend fun removeAlbumCover(albumKey: String)

    @Query("SELECT identity_id FROM track_cover")
    suspend fun identitiesWithCovers(): List<Long>
}
