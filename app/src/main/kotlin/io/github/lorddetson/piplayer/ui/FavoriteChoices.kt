package io.github.lorddetson.piplayer.ui

import android.content.Context
import android.util.Log
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.ui.songs.FavoriteBatch
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * What the screens do with favorites (docs/library.md §15.2): a song is one through its track identity, which it has
 * once its tags are read; an album through its album key.
 */
class FavoriteChoices(context: Context) {

    enum class Result { DONE, NOT_READ, FAILED }

    private val app = context.applicationContext
    private val favorites get() = UserData.favorites(app)

    /** The reference of the song [fileId]: its identity, null before its tags are read, and the key it was read by. */
    private suspend fun refOf(fileId: Long): TrackRef? = withContext(Dispatchers.IO) {
        if (Library.mayRead(app)) Library.trackRefs(app).refOf(fileId) else null
    }

    private suspend fun identityOf(fileId: Long): Long? = refOf(fileId)?.identityId

    suspend fun isFavorite(fileId: Long): Boolean = safely(false) { identityOf(fileId)?.let { favorites.isFavorite(it) } == true }

    /** Whether the song [fileId] is a favorite, again whenever it or the identities change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun favoriteFlow(fileId: Long): Flow<Boolean> = IdentityAttacher.changes
        .mapLatest { safely(null) { identityOf(fileId) } }
        .flatMapLatest { identity -> identity?.let { favorites.isFavoriteFlow(it) } ?: flowOf(false) }
        .catch { e ->
            Log.w(TAG, "A favorite could not be read", e)
            emit(false)
        }

    suspend fun setSong(fileId: Long, favorite: Boolean): Result = safely(Result.FAILED) {
        val ref = refOf(fileId)
        val identity = ref?.identityId ?: return@safely Result.NOT_READ
        // Checked again in the write transaction: a takeover may have deleted it since (docs/library.md §8.2).
        if (!favorites.set(identity, favorite, ref.contentKey?.let(Hex::bytes))) return@safely Result.NOT_READ
        Result.DONE
    }

    /** How many selected songs [fileIds] were written ([changed]) and how many are [notRead] yet (docs/library.md §20.3). */
    data class BatchResult(val changed: Int, val notRead: Int)

    /** The identities of the songs [fileIds], read in chunks; songs whose tags are not read yet have none. */
    private suspend fun refsOf(fileIds: List<Long>): Map<Long, TrackRef> = withContext(Dispatchers.IO) {
        if (Library.mayRead(app)) Library.trackRefs(app).refsOf(fileIds) else emptyMap()
    }

    /**
     * Of the selected songs [fileIds], how many have their tags read and how many of those are not favorites, from the user
     * database itself (docs/library.md §20.3); null when it cannot be read now.
     */
    suspend fun batchState(fileIds: List<Long>): FavoriteBatch? = safely(null) {
        val refs = refsOf(fileIds)
        val identities = fileIds.mapNotNull { refs[it]?.identityId }
        val favorite = withContext(Dispatchers.IO) { favorites.favoriteIdentities(identities) }
        FavoriteBatch(counted = identities.size, notFavorite = identities.count { it !in favorite })
    }

    /** Makes the selected songs [fileIds] favorites, or not, in one transaction; null when it failed. */
    suspend fun setSongs(fileIds: List<Long>, favorite: Boolean): BatchResult? = safely(null) {
        val refs = refsOf(fileIds)
        // Each song once: a playlist or the queue may hold one twice.
        val songs = fileIds.distinct().mapNotNull { id ->
            refs[id]?.let { ref -> ref.identityId?.let { Favorites.Song(id, it, ref.contentKey?.let(Hex::bytes)) } }
        }
        val written = withContext(Dispatchers.IO) { favorites.setAll(songs, favorite) }
        favoriteBatchResult(fileIds, { refs[it]?.identityId }, written)
    }

    fun albumFlow(albumKey: String): Flow<Boolean> = favorites.isFavoriteAlbumFlow(albumKey).catch { emit(false) }

    suspend fun setAlbum(albumKey: String, favorite: Boolean): Result = safely(Result.FAILED) {
        favorites.setAlbum(albumKey, favorite)
        Result.DONE
    }

    /** [block], or [fallback] when a database cannot be read or written now; the screens must not go down for it. */
    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "A favorite could not be changed", e)
        fallback
    }

    companion object {
        private const val TAG = "FavoriteChoices"

        /** Changes run here, not in a screen's scope, so that a rotation does not cut one short. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

/**
 * The message's counts for the selected songs [fileIds] after [written] (docs/library.md §20.3): the songs that changed, and
 * as still being read each song without an identity ([identityOf]) and each whose identity went before the write, as the
 * song menu says of one (§15.2).
 */
internal fun favoriteBatchResult(fileIds: List<Long>, identityOf: (Long) -> Long?, written: Favorites.Written): FavoriteChoices.BatchResult =
    FavoriteChoices.BatchResult(changed = written.changed, notRead = fileIds.distinct().count { identityOf(it) == null } + written.gone)
