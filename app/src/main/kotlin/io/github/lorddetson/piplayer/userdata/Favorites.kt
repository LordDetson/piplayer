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

    /**
     * Makes the song of [identityId], read with the file's [contentKey], a favorite, or not; a favorite keeps the time it
     * first became one. The identity is checked in the write transaction ([current]); false when the song has none now.
     */
    suspend fun set(identityId: Long, favorite: Boolean, contentKey: ByteArray? = null): Boolean = database.withWriteTransaction {
        val identity = database.identities().current(identityId, contentKey) ?: return@withWriteTransaction false
        if (favorite) dao.add(identity, clock()) else dao.remove(identity)
        database.user().countChange()
        true
    }

    /** Which of [identityIds] are favorites, read in chunks (docs/library.md §20.3). */
    suspend fun favoriteIdentities(identityIds: Collection<Long>): Set<Long> =
        identityIds.distinct().chunked(CHUNK).flatMapTo(HashSet()) { dao.favoritesAmong(it) }

    /**
     * Makes the songs of [songs] (each an identity with the content key it was read by) favorites, or not, in one
     * transaction that checks each identity as [set] does and raises the change counter once when anything changed: how
     * many songs changed (a favorite made one again does not, nor a song given twice) and how many were left out since
     * their identity is gone (docs/library.md §20.3).
     */
    suspend fun setAll(songs: List<Pair<Long, ByteArray?>>, favorite: Boolean): Written = database.withWriteTransaction {
        var gone = 0
        val identities = LinkedHashSet<Long>()
        for ((identityId, contentKey) in songs) {
            val identity = database.identities().current(identityId, contentKey)
            if (identity == null) gone++ else identities += identity
        }
        val already = identities.chunked(CHUNK).flatMapTo(HashSet()) { dao.favoritesAmong(it) }
        val changing = identities.filter { (it in already) != favorite }
        val now = clock()
        for (identity in changing) if (favorite) dao.add(identity, now) else dao.remove(identity)
        if (changing.isNotEmpty()) database.user().countChange()
        Written(changing.size, gone)
    }

    /** What [setAll] did: how many songs [changed] and how many were left out since their identity is [gone]. */
    data class Written(val changed: Int, val gone: Int)

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

        /** Identities of one read: within SQLite's limit on bound values. */
        private const val CHUNK = 500

        /** The release ID of an album key made of one, as 16 bytes; null for any other key. */
        fun releaseMbidOf(albumKey: String): ByteArray? =
            if (albumKey.startsWith(RELEASE_PREFIX)) Hex.mbidBytes(albumKey.removePrefix(RELEASE_PREFIX)) else null
    }
}
