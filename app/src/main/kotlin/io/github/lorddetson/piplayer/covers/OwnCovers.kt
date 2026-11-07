package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A copy of a chosen image in the covers folder (docs/library.md §13.2). */
data class CoverCopy(val fileName: String, val width: Int, val height: Int)

/**
 * The user's own covers (docs/library.md §13.2): references in `user.db`, copies in [dir]. Read once, then kept in memory;
 * every change raises [version].
 */
class OwnCovers(private val database: UserDatabase, val dir: File, private val clock: () -> Long = System::currentTimeMillis) :
    OwnCoverIndex {

    private val mutableVersion = MutableStateFlow(0L)

    /** Raised by every change, so that views load their covers again. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    /** Called after each change, for the loader to forget what it holds. */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** Reads the references, once, and deletes copies that none names. */
    suspend fun load(): Unit = Unit

    override fun track(identityId: Long): String? = null

    override fun album(albumKey: String): String? = null

    suspend fun setTrack(identityId: Long, copy: CoverCopy): Unit = Unit

    suspend fun setAlbum(albumKey: String, copy: CoverCopy): Unit = Unit

    suspend fun removeTrack(identityId: Long): Unit = Unit

    suspend fun removeAlbum(albumKey: String): Unit = Unit

    /** Forgets every own cover and deletes the copies; tests start so. */
    suspend fun clear(): Unit = Unit

    /** The identities that hold an own cover, which the attach pass never deletes (docs/library.md §8.1). */
    suspend fun identitiesWithCovers(): Set<Long> = emptySet()
}
