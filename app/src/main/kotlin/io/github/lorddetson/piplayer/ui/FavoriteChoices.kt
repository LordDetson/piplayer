package io.github.lorddetson.piplayer.ui

import android.content.Context
import android.util.Log
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
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

    /** The identity of the song [fileId], or null before its tags are read. */
    private suspend fun identityOf(fileId: Long): Long? = withContext(Dispatchers.IO) {
        if (Library.mayRead(app)) Library.trackRefs(app).refOf(fileId)?.identityId else null
    }

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
        val identity = identityOf(fileId) ?: return@safely Result.NOT_READ
        favorites.set(identity, favorite)
        Result.DONE
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
