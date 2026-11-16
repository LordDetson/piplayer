package io.github.lorddetson.piplayer.covers

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.caa.CaaException
import io.github.lorddetson.piplayer.net.SendBlockedException
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.db.CoverFileRow
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.lorddetson.piplayer.library.tagpass.TagPassState
import kotlinx.coroutines.withContext

/** The library database's answers for covers (docs/library.md §13.1), with each file's identity from [user]. */
class LibraryCoverFacts(
    private val access: LibraryTrackRefs.Access,
    private val user: UserDatabase,
    /** The hidden files of the lists (§3.5), so that an artist's albums are those of the artist page. */
    private val hidden: () -> HiddenFiles = { HiddenFiles() },
) : CoverFacts {

    private suspend fun <T> read(block: suspend (LibraryDatabase) -> T): T = withContext(Dispatchers.IO) {
        access.read { database -> kotlinx.coroutines.runBlocking { block(database) } }
    }

    override suspend fun file(fileId: Long): CoverFile? = read { it.library().coverFile(fileId) }?.let { cover(it) }

    override suspend fun albumFile(albumKey: String): CoverFile? = read { it.library().albumCoverFile(albumKey) }?.let { cover(it) }

    override suspend fun artistAlbums(artistKey: String): List<ArtistAlbum> {
        val shown = hidden()
        return read { database ->
            val albums = LibraryLists(database, shown).artistAlbums(artistKey).map { it.key }
            val dao = database.library()
            val withPictures = albums.chunked(KEY_CHUNK).flatMap { dao.albumsWithPictures(it) }.toSet()
            val withDecided = albums.chunked(KEY_CHUNK).flatMap { dao.albumsWithDecidedCovers(it) }.toSet()
            val withReleases = albums.chunked(KEY_CHUNK).flatMap { dao.albumsWithReleases(it) }.toSet()
            albums.map { ArtistAlbum(it, it in withPictures, decided = it in withDecided, release = it in withReleases) }
        }
    }

    override suspend fun artistFile(artistKey: String): CoverFile? = read { it.library().artistCoverFile(artistKey) }?.let { cover(it) }

    override suspend fun albumDecidedCover(albumKey: String): Long? =
        read { it.library().albumDecidedCover(albumKey) }?.toLongOrNull()

    override suspend fun albumRelease(albumKey: String): CoverRelease? =
        read { it.library().albumRelease(albumKey) }?.let { release(it.releaseMbid, it.releaseGroupMbid) }

    /** A release as the library holds it; an empty value is none. */
    private fun release(release: String?, group: String?): CoverRelease? =
        release?.takeIf { it.isNotEmpty() }?.let { CoverRelease(it, group?.takeIf(String::isNotEmpty)) }

    private suspend fun cover(row: CoverFileRow) = CoverFile(
        fileId = row.fileId,
        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId).toString(),
        size = row.size,
        mtime = row.mtime,
        embedded = row.embedded,
        identityId = row.contentKey?.let { user.identities().idOfKey(Hex.bytes(it)) },
        albumKey = row.albumKey,
        contentKey = row.contentKey,
        decidedCover = row.decidedCover?.toLongOrNull(),
        release = release(row.releaseMbid, row.releaseGroupMbid),
    )

    private companion object {
        /** Album keys bound in one query, well under SQLite's limit of arguments. */
        const val KEY_CHUNK = 500
    }
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

    @Volatile
    private var savedCovers: SavedCovers? = null

    /** The accepted covers of identification (TI §5.2), in the no-backup files; one instance for the process. */
    fun saved(context: Context): SavedCovers = savedCovers ?: synchronized(this) {
        savedCovers ?: run {
            ProcessRole.current(context).requireMain("Saved covers")
            val app = context.applicationContext
            SavedCovers(UserData.database(app), SavedCovers.directory(app.noBackupFilesDir)).also { savedCovers = it }
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
            hidden = { LibrarySettings(app).readHiddenFiles() },
        )
    }

    fun loader(context: Context): CoverLoader<Bitmap> = coverLoader ?: synchronized(this) {
        coverLoader ?: run {
            val app = context.applicationContext
            val own = own(app)
            val saved = saved(app)
            val gate = (app as PiPlayerApplication).onlineGate
            val names = saved.names()
            CoverLoader(
                plan = { request ->
                    own.load()
                    CoverPlan.of(request, facts(app), own, names, online = gate.isOpen())
                },
                sources = AndroidCoverSources(
                    app,
                    own.dir,
                    saved,
                    // The client comes into being with the first lazy cover, not with the loader.
                    AndroidCoverSources.caa({ sources, size -> IdentificationServices.of(app).coverArtArchive.cover(sources, size) }, gate, gate.open),
                ),
                codec = BitmapCodec,
                disk = CoverDiskCache(File(app.cacheDir, CACHE_DIR)),
                memoryWeight = memoryWeight(),
                failures = { e ->
                    // Online lookups off, no network or a busy Cover Art Archive are expected; they need no stack trace.
                    if (e is SendBlockedException || e is CaaBusyException || e is CaaException) {
                        android.util.Log.d(TAG, "A Cover Art Archive cover could not be loaded now: ${e.message}")
                    } else {
                        android.util.Log.i(TAG, "A cover could not be loaded now", e)
                    }
                },
            ).also { loader ->
                own.onChange = loader::invalidate
                scope.launch {
                    CoverInvalidations.of(
                        Library.status(app).map { it.revision },
                        TagPassState.progress.map { it.running },
                        IdentityAttacher.changes,
                        LibrarySettings(app).hiddenFiles,
                        Library.decisionVersion(app),
                        gate.open,
                    )
                        .collect { change ->
                            loader.invalidate()
                            // After the memory is emptied, so that the covers on screen load what is there now.
                            if (change == CoverChange.RELOAD) reloads.update { it + 1 }
                        }
                }
                coverLoader = loader
            }
        }
    }

    /** Raised after a change that loads the covers on screen again besides own covers (docs/library.md §13.3). */
    private val reloads = MutableStateFlow(0L)

    /**
     * What the covers on screen and the session load again on: a change of own covers, a decision that changed decided
     * values, or online lookups turned on or off (docs/library.md §13.3, §13.6). It emits once at first and then at each.
     */
    fun changes(context: Context): StateFlow<Any> = coverChanges ?: synchronized(this) {
        coverChanges ?: run {
            val app = context.applicationContext
            loader(app)
            val own = own(app)
            combine(own.version, reloads) { ownVersion, reload -> ownVersion to reload }
                .stateIn(scope, SharingStarted.Eagerly, own.version.value to reloads.value)
                .also { coverChanges = it }
        }
    }

    @Volatile
    private var coverChanges: StateFlow<Any>? = null

    @Volatile
    private var coverUris: CoverUris? = null

    /**
     * The URIs of covers for controllers (docs/library.md §13.8.2): one tag for the process, the loader's generation as
     * the version. Building a URI reads nothing; the loader comes into being with the first one.
     */
    fun uris(context: Context): CoverUris = coverUris ?: synchronized(this) {
        coverUris ?: run {
            val app = context.applicationContext
            CoverUris(CoverUris.authorityOf(app.packageName), generation = { loader(app).generation }).also { coverUris = it }
        }
    }

    /** Lives as long as the process, as the loader does. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Forgets every cached cover, in memory and on disk; tests start so. */
    fun clearCaches(context: Context) = loader(context).clear()

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

    enum class Result { SAVED, NOT_READ, NO_IMAGE, FAILED, REMOVED }

    private val app = context.applicationContext
    private val own = Covers.own(app)

    /** The identity of the song [fileId] with the key it was read by; it has one once the tag pass has read it. */
    private suspend fun songOf(fileId: Long): Pair<Long, ByteArray?>? =
        Covers.facts(app).file(fileId)?.let { file -> file.identityId?.let { it to file.contentKey?.let(Hex::bytes) } }

    private suspend fun identityOf(fileId: Long): Long? = songOf(fileId)?.first

    /** Whether the song [fileId] can have an own cover now. */
    suspend fun canChooseForSong(fileId: Long): Boolean = safely(false) { identityOf(fileId) != null }

    suspend fun chooseForSong(fileId: Long, image: Uri): Result = safely(Result.FAILED) {
        val (identity, key) = songOf(fileId) ?: return@safely Result.NOT_READ
        val copy = copy(image) ?: return@safely Result.NO_IMAGE
        // The copy can take seconds; the identity is checked again where the cover is written (docs/library.md §8.2).
        if (!own.setTrack(identity, copy, key)) return@safely Result.NOT_READ
        Result.SAVED
    }

    suspend fun chooseForAlbum(albumKey: String, image: Uri): Result = safely(Result.FAILED) {
        val copy = copy(image) ?: return@safely Result.NO_IMAGE
        own.setAlbum(albumKey, copy)
        Result.SAVED
    }

    /** [block], or [fallback] when a database cannot be read or written now; the screens must not go down for it. */
    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("CoverChoices", "An own cover could not be changed", e)
        fallback
    }

    private suspend fun copy(image: Uri): CoverCopy? {
        // Read first, so that the clean-up of copies without references runs before this copy exists.
        own.load()
        return withContext(Dispatchers.IO) { CoverImport.copy(app, image, own.dir) }
    }

    suspend fun removeForSong(fileId: Long): Result = safely(Result.FAILED) {
        songOf(fileId)?.let { (identity, key) -> own.removeTrack(identity, key) }
        Result.REMOVED
    }

    suspend fun removeForAlbum(albumKey: String): Result = safely(Result.FAILED) {
        own.removeAlbum(albumKey)
        Result.REMOVED
    }

    suspend fun songHasOwn(fileId: Long): Boolean = safely(false) {
        own.load()
        identityOf(fileId)?.let(own::track) != null
    }

    suspend fun albumHasOwn(albumKey: String): Boolean = safely(false) {
        own.load()
        own.album(albumKey) != null
    }

    companion object {
        /**
         * Changes run here, not in a screen's scope: a rotation while a chosen image is copied must not cut the change
         * short after the copy is written.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
