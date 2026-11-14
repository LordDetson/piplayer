package io.github.lorddetson.piplayer.userdata

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** A favorite song, by its track identity (docs/library.md §15.1). */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * A favorite album, by its album key (docs/library.md §6.2, §15.1); an album with a release ID has the key
 * `mbid:<release ID>` and [releaseMbid] holds that ID as 16 bytes.
 */
@Entity(tableName = "favorite_album")
data class FavoriteAlbumEntity(
    @PrimaryKey @ColumnInfo(name = "album_key") val albumKey: String,
    @ColumnInfo(name = "release_mbid") val releaseMbid: ByteArray?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is FavoriteAlbumEntity && albumKey == other.albumKey &&
        releaseMbid.contentEquals(other.releaseMbid) && addedAt == other.addedAt

    override fun hashCode(): Int = albumKey.hashCode()
}

/** An identity seen on an album that a favorite or an own cover refers to (docs/library.md §15.3). */
@Entity(tableName = "album_member", primaryKeys = ["album_key", "identity_id"], indices = [Index("identity_id")])
data class AlbumMemberEntity(
    @ColumnInfo(name = "album_key") val albumKey: String,
    @ColumnInfo(name = "identity_id") val identityId: Long,
)

/** A favorite song with its identity's content key, for the mirror. */
data class FavoriteKeyRow(
    @ColumnInfo(name = "identity_id") val identityId: Long,
    @ColumnInfo(name = "content_key") val contentKey: ByteArray,
)

@Dao
interface FavoriteDao {

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE identity_id = :identityId)")
    suspend fun isFavorite(identityId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE identity_id = :identityId)")
    fun isFavoriteFlow(identityId: Long): Flow<Boolean>

    @Query("INSERT OR IGNORE INTO favorite (identity_id, added_at) VALUES (:identityId, :addedAt)")
    suspend fun add(identityId: Long, addedAt: Long)

    @Query("DELETE FROM favorite WHERE identity_id = :identityId")
    suspend fun remove(identityId: Long)

    @Query("SELECT * FROM favorite ORDER BY identity_id")
    suspend fun favorites(): List<FavoriteEntity>

    @Query("SELECT f.identity_id, i.content_key FROM favorite f JOIN track_identity i ON i.id = f.identity_id")
    suspend fun favoriteKeys(): List<FavoriteKeyRow>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_album WHERE album_key = :albumKey)")
    suspend fun isFavoriteAlbum(albumKey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_album WHERE album_key = :albumKey)")
    fun isFavoriteAlbumFlow(albumKey: String): Flow<Boolean>

    @Query("SELECT * FROM favorite_album ORDER BY album_key")
    suspend fun favoriteAlbums(): List<FavoriteAlbumEntity>

    @Query("SELECT * FROM favorite_album WHERE album_key = :albumKey")
    suspend fun favoriteAlbum(albumKey: String): FavoriteAlbumEntity?

    @Query("INSERT OR REPLACE INTO favorite_album (album_key, release_mbid, added_at) VALUES (:albumKey, :releaseMbid, :addedAt)")
    suspend fun putAlbum(albumKey: String, releaseMbid: ByteArray?, addedAt: Long)

    @Query("DELETE FROM favorite_album WHERE album_key = :albumKey")
    suspend fun removeAlbum(albumKey: String)

    @Query("SELECT * FROM album_member ORDER BY album_key, identity_id")
    suspend fun members(): List<AlbumMemberEntity>

    @Query("INSERT OR IGNORE INTO album_member (album_key, identity_id) VALUES (:albumKey, :identityId)")
    suspend fun addMember(albumKey: String, identityId: Long)

    @Query("DELETE FROM album_member WHERE album_key = :albumKey")
    suspend fun removeMembers(albumKey: String)

    /** Members whose identity is gone (docs/library.md §15.3). */
    @Query("DELETE FROM album_member WHERE identity_id NOT IN (SELECT id FROM track_identity)")
    suspend fun removeLostMembers()
}
