package io.github.lorddetson.piplayer.covers

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.CoverFileRow
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The library database's answers for covers (docs/library.md §13.1), with each file's identity from [user]. */
class LibraryCoverFacts(private val access: LibraryTrackRefs.Access, private val user: UserDatabase) : CoverFacts {

    private suspend fun <T> read(block: suspend (LibraryDatabase) -> T): T = withContext(Dispatchers.IO) {
        access.read { database -> kotlinx.coroutines.runBlocking { block(database) } }
    }

    override suspend fun file(fileId: Long): CoverFile? = read { it.library().coverFile(fileId) }?.let { cover(it) }

    override suspend fun albumFile(albumKey: String): CoverFile? = read { it.library().albumCoverFile(albumKey) }?.let { cover(it) }

    override suspend fun artistAlbums(artistKey: String): List<ArtistAlbum> =
        read { it.library().artistCoverAlbums(artistKey) }.map { ArtistAlbum(it.albumKey, it.embedded) }

    override suspend fun artistFile(artistKey: String): CoverFile? = read { it.library().artistCoverFile(artistKey) }?.let { cover(it) }

    private suspend fun cover(row: CoverFileRow) = CoverFile(
        fileId = row.fileId,
        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId).toString(),
        size = row.size,
        mtime = row.mtime,
        embedded = row.embedded,
        identityId = row.contentKey?.let { user.identities().idOfKey(Hex.bytes(it)) },
        albumKey = row.albumKey,
    )
}

/** Facts of a library that may not be read: nothing. */
private object NoFacts : CoverFacts {
    override suspend fun file(fileId: Long): CoverFile? = null
    override suspend fun albumFile(albumKey: String): CoverFile? = null
    override suspend fun artistAlbums(artistKey: String): List<ArtistAlbum> = emptyList()
    override suspend fun artistFile(artistKey: String): CoverFile? = null
}

/** The covers of the main process: one loader for the screens and the session, and the own covers (§13.3). */
object Covers {

    // Both hold the application context only, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var ownCovers: OwnCovers? = null

    @Volatile
    private var coverLoader: CoverLoader<Bitmap>? = null

    fun own(context: Context): OwnCovers = ownCovers ?: synchronized(this) {
        ownCovers ?: run {
            ProcessRole.current(context).requireMain("Own covers")
            val app = context.applicationContext
            OwnCovers(UserData.database(app), File(app.noBackupFilesDir, OWN_DIR)).also { ownCovers = it }
        }
    }

    /** The library's facts while the library may be read, otherwise none. */
    fun facts(context: Context): CoverFacts {
        val app = context.applicationContext
        if (!Library.mayRead(app)) return NoFacts
        return LibraryCoverFacts(
            object : LibraryTrackRefs.Access {
                override fun <T> read(block: (LibraryDatabase) -> T): T = Library.read(app, block)
            },
            UserData.database(app),
        )
    }

    fun loader(context: Context): CoverLoader<Bitmap> = coverLoader ?: synchronized(this) {
        coverLoader ?: run {
            val app = context.applicationContext
            val own = own(app)
            CoverLoader(
                plan = { request ->
                    own.load()
                    CoverPlan.of(request, facts(app), own)
                },
                sources = AndroidCoverSources(app, own.dir),
                codec = BitmapCodec,
                disk = CoverDiskCache(File(app.cacheDir, CACHE_DIR)),
                memoryWeight = memoryWeight(),
                failures = { e -> android.util.Log.i(TAG, "A cover could not be loaded now", e) },
            ).also { loader ->
                own.onChange = loader::invalidate
                coverLoader = loader
            }
        }
    }

    /** An eighth of the heap, at most 32 MB (docs/library.md §13.3). */
    private fun memoryWeight(): Long = minOf(Runtime.getRuntime().maxMemory() / 8, 32L * 1024 * 1024)

    /** Where the copies of own covers live, in the no-backup files. */
    const val OWN_DIR = "covers"

    /** Where the disk cache lives, in the cache directory. */
    const val CACHE_DIR = "covers"

    private const val TAG = "Covers"
}

/** What the screens do with own covers (docs/library.md §13.2). */
class CoverChoices(context: Context) {

    enum class Result { SAVED, NOT_READ, NO_IMAGE }

    private val app = context.applicationContext
    private val own = Covers.own(app)

    /** The identity of the song [fileId]; it has one once the tag pass has read it. */
    private suspend fun identityOf(fileId: Long): Long? = Covers.facts(app).file(fileId)?.identityId

    /** Whether the song [fileId] can have an own cover now. */
    suspend fun canChooseForSong(fileId: Long): Boolean = identityOf(fileId) != null

    suspend fun chooseForSong(fileId: Long, image: Uri): Result {
        val identity = identityOf(fileId) ?: return Result.NOT_READ
        val copy = copy(image) ?: return Result.NO_IMAGE
        own.setTrack(identity, copy)
        return Result.SAVED
    }

    suspend fun chooseForAlbum(albumKey: String, image: Uri): Result {
        val copy = copy(image) ?: return Result.NO_IMAGE
        own.setAlbum(albumKey, copy)
        return Result.SAVED
    }

    private suspend fun copy(image: Uri): CoverCopy? {
        // Read first, so that the clean-up of copies without references runs before this copy exists.
        own.load()
        return withContext(Dispatchers.IO) { CoverImport.copy(app, image, own.dir) }
    }

    suspend fun removeForSong(fileId: Long) {
        identityOf(fileId)?.let { own.removeTrack(it) }
    }

    suspend fun removeForAlbum(albumKey: String) = own.removeAlbum(albumKey)

    suspend fun songHasOwn(fileId: Long): Boolean {
        own.load()
        return identityOf(fileId)?.let(own::track) != null
    }

    suspend fun albumHasOwn(albumKey: String): Boolean {
        own.load()
        return own.album(albumKey) != null
    }
}
