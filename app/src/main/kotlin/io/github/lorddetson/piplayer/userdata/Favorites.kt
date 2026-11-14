package io.github.lorddetson.piplayer.userdata

import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.identity.Hex
import kotlinx.coroutines.flow.Flow

/**
 * Favorite songs and albums (docs/library.md §15.2). Every change raises the change counter in the same transaction, so
 * that the mirror brings it into the library database.
 */
class Favorites(private val database: UserDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    private val dao get() = database.favorites()

    suspend fun isFavorite(identityId: Long): Boolean = dao.isFavorite(identityId)

    fun isFavoriteFlow(identityId: Long): Flow<Boolean> = dao.isFavoriteFlow(identityId)

    /** Makes the song of [identityId] a favorite, or not; a favorite keeps the time it first became one. */
    suspend fun set(identityId: Long, favorite: Boolean) = database.withWriteTransaction {
        if (favorite) dao.add(identityId, clock()) else dao.remove(identityId)
        database.user().countChange()
    }

    suspend fun isFavoriteAlbum(albumKey: String): Boolean = dao.isFavoriteAlbum(albumKey)

    fun isFavoriteAlbumFlow(albumKey: String): Flow<Boolean> = dao.isFavoriteAlbumFlow(albumKey)

    /** Makes the album [albumKey] a favorite, or not (docs/library.md §15.1). */
    suspend fun setAlbum(albumKey: String, favorite: Boolean) = database.withWriteTransaction {
        if (favorite) {
            if (dao.favoriteAlbum(albumKey) == null) dao.putAlbum(albumKey, releaseMbidOf(albumKey), clock())
        } else {
            dao.removeAlbum(albumKey)
        }
        database.user().countChange()
    }

    /** Forgets every favorite song and album and their members; tests start so. */
    suspend fun clear() = database.withWriteTransaction {
        usePrepared("DELETE FROM favorite") { it.step() }
        usePrepared("DELETE FROM favorite_album") { it.step() }
        usePrepared("DELETE FROM album_member") { it.step() }
        database.user().countChange()
    }

    companion object {
        /** The prefix of an album key made of a release ID (`AlbumKey`). */
        const val RELEASE_PREFIX = "mbid:"

        /** The release ID of an album key made of one, as 16 bytes; null for any other key. */
        fun releaseMbidOf(albumKey: String): ByteArray? =
            if (albumKey.startsWith(RELEASE_PREFIX)) Hex.mbidBytes(albumKey.removePrefix(RELEASE_PREFIX)) else null
    }
}
