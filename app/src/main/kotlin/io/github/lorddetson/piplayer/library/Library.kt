package io.github.lorddetson.piplayer.library

import java.util.concurrent.atomic.AtomicInteger
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.StoredPlace
import io.github.lorddetson.piplayer.library.identity.FingerprintStep
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.identity.LibraryFingerprints
import io.github.lorddetson.piplayer.library.identity.LibraryTrackRefs
import io.github.lorddetson.piplayer.library.mirror.UserMirror
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.rules.RulesSource
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import io.github.lorddetson.piplayer.library.sync.MediaStoreIndex
import io.github.lorddetson.piplayer.library.tagpass.PassGate
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.DurationWalks
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.tagpass.TagPassRunner
import io.github.lorddetson.piplayer.library.tagpass.TaggerPassReader
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.settings.DecodedLegacyEncoding
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.tagging.Tagger
import io.github.lorddetson.piplayer.userdata.FolderRuleEntity
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.withTimeoutOrNull

/** The library of this process: its database and its sync, opened once, in the main process only (docs/library.md §1). */
object Library {

    private class Opened(
        val database: LibraryDatabase,
        val sync: LibrarySync,
        val identities: IdentityAttacher,
        val trackRefs: LibraryTrackRefs,
        val mirror: UserMirror,
    ) {
        @Volatile
        var pass: TagPassRunner? = null
    }

    /** When the tag pass may read; the app and the playback service tell it what they see. */
    val passGate = PassGate()

    /** Tests turn the tag pass off, because it would read the device's real library under them. */
    @Volatile
    var tagPassEnabled = true

    /** What the tag pass reads with in a test over a fake media index, whose files `:tagger` could not open. */
    @VisibleForTesting
    @Volatile
    var passReaderForTests: PassReader? = null

    private val opened = MutableStateFlow<Opened?>(null)
    private val lock = Any()
    private val access = ReentrantReadWriteLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    @Volatile
    private var indexOverride: MediaIndex? = null

    fun database(context: Context): LibraryDatabase = opened(context).database

    /**
     * Runs [block] on the library database while it cannot be closed: lists read it through here. The bundled SQLite
     * does not check for a closed connection, so closing it under a read would take the whole process down.
     */
    fun <T> read(context: Context, block: (LibraryDatabase) -> T): T = access.read { block(database(context)) }

    fun sync(context: Context): LibrarySync = opened(context).sync

    /** The attach pass of the track identities (docs/library.md §8.2). */
    fun identities(context: Context): IdentityAttacher = opened(context).identities

    /** The mirror of favorites and plays into the library database (docs/library.md §15.4). */
    fun mirror(context: Context): UserMirror = opened(context).mirror

    /** Raised after every run of the mirror; it follows the library across [reopen] and a rebuild. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun mirrorVersion(context: Context): Flow<Long> {
        opened(context)
        return opened.filterNotNull().flatMapLatest { it.mirror.version }
    }

    /**
     * Track references of the saved queue (docs/playback.md §4.8). They read the library through [read], so a rebuild
     * never closes the database under them.
     */
    fun trackRefs(context: Context): LibraryTrackRefs = opened(context).trackRefs

    private fun newTrackRefs(context: Context, user: UserDatabase = UserData.database(context)) = LibraryTrackRefs(
        object : LibraryTrackRefs.Access {
            override fun <T> read(block: (LibraryDatabase) -> T): T = read(context, block)
        },
        user,
    )

    /** The status of the library's sync; it follows the library across [reopen]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun status(context: Context): Flow<LibraryStatus> {
        opened(context)
        return opened.filterNotNull().flatMapLatest { it.sync.status }
    }

    /** Calls of [start] that found access, for the test that resumes do not start the library again. */
    @VisibleForTesting
    val starts = AtomicInteger()

    /**
     * Watches the media index and syncs now, if the app may read it; called when the main process starts and when access
     * is newly granted. The watcher and the listeners are registered once, but every call asks the tag pass and the attach
     * pass for a run (and a reopened library is started this way in tests), so callers call it on a new grant only, never
     * on every resume.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!mayRead(app)) return
        starts.incrementAndGet()
        val sync = sync(app)
        startTagPass(app)
        identities(app).request()
        mirror(app).request()
        synchronized(lock) {
            if (observer != null) return
            // Every change of the index's audio rows, on every volume; a burst brings one sync a second later.
            val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    opened.value?.sync?.requestSoon()
                }
            }
            app.getSystemService(PowerManager::class.java).addThermalStatusListener { status ->
                passGate.setHot(status >= PowerManager.THERMAL_STATUS_MODERATE)
            }
            app.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                true,
                watcher,
            )
            observer = watcher
            // A change of the folder rules is applied by a sync at once (docs/library.md §3.1, §3.4).
            scope.launch {
                UserData.database(app).user().rulesCounterFlow().counterChanges().collect { opened.value?.sync?.request() }
            }
            // Every change of mirrored user data, and of the own covers whose albums need members, asks the mirror.
            scope.launch {
                UserData.database(app).user().changeCounterFlow().counterChanges().collect { opened.value?.mirror?.request() }
            }
            scope.launch {
                Covers.own(app).version.drop(1).collect { opened.value?.mirror?.request() }
            }
            // A change of the legacy encoding decodes the stored files again, reading none (docs/library.md §5.7).
            scope.launch {
                LibrarySettings(app).legacyEncoding.drop(1).collect { opened.value?.pass?.redecode() }
            }
        }
        sync.request()
    }

    /** The tag pass of the open library, once; it reads when it has something to read (docs/library.md §5.5). */
    private fun startTagPass(app: Context) {
        if (!tagPassEnabled) return
        val library = opened(app)
        val runner = synchronized(lock) {
            library.pass ?: tagPassRunner(app, library).also { library.pass = it }
        }
        runner.start()
    }

    private fun tagPassRunner(app: Context, library: Opened): TagPassRunner {
        val reader = TaggerPassReader(app, Tagger.client(app))
        val pass = TagPass(
            library.database,
            library.sync,
            passReaderForTests ?: reader,
            cyrillicLanguage = {
                val locales = listOf(Resources.getSystem().configuration.locales, app.resources.configuration.locales)
                LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
            },
            legacyEncoding = { LibrarySettings(app).readLegacyEncoding() },
            decodedEncoding = DecodedLegacyEncoding(app),
            gate = passGate::awaitOpen,
            onBatch = { rows ->
                SongList.applyInPlace(rows)
                // Favorites and play counts come back with the keys (docs/library.md §15.4).
                library.mirror.requestFiles(rows.map { it.fileId })
            },
        )
        val walks = DurationWalks(library.database, library.sync, reader, passGate, onBatch = SongList::applyLengths)
        return TagPassRunner(pass, library.sync.status, passGate, walks = walks, onRunEnd = library.identities::request)
    }

    /** A sync now, and a run of the tag pass after it; what the settings offer (docs/library.md §5.6). */
    fun refresh(context: Context) {
        val library = opened(context.applicationContext)
        library.sync.request()
        library.pass?.runAgain()
    }

    /** Has the tag pass read every file again, those it could not read too. Lists keep what they show meanwhile. */
    suspend fun readAllTagsAgain(context: Context) {
        val library = opened(context.applicationContext)
        library.sync.exclusive { library.database.library().forgetReaderVersions() }
        library.pass?.readAllAgain()
    }

    /**
     * Deletes the library database and builds it again (docs/library.md §8.5): the files that are still at their places
     * keep their IDs and the library its ID, as long as the old database can be read. User data is not touched; the
     * identities attach again as the tag pass brings the keys back.
     */
    suspend fun rebuild(context: Context) {
        // A second confirmation while one runs is ignored.
        if (!rebuilding.tryLock()) return
        try {
            rebuildOnce(context.applicationContext)
        } finally {
            rebuilding.unlock()
        }
    }

    private val rebuilding = Mutex()

    private suspend fun rebuildOnce(app: Context) {
        val old = opened(app)
        stop(old)
        val kept = try {
            val dao = old.database.library()
            Kept(dao.places(), dao.libraryId(), maxOf(dao.lastGivenFileId(), dao.lastFileId()))
        } catch (e: Exception) {
            Log.w(TAG, "The library database could not be read before the rebuild", e)
            null
        }
        access.write {
            synchronized(lock) {
                old.database.close()
                SQLiteDatabase.deleteDatabase(file(app))
                opened.value = open(app, kept)
            }
        }
        SongList.forget()
        if (mayRead(app)) {
            startTagPass(app)
            identities(app).request()
            mirror(app).request()
            sync(app).request()
        }
    }

    /** Ends the work on [library], so that its database may be closed. */
    private suspend fun stop(library: Opened) {
        library.pass?.stopAndJoin()
        library.sync.awaitIdle()
        // A sync asked for after that, by a change of the index or of the user data, must not run while the database
        // closes under it.
        library.sync.closeAndJoin()
        library.identities.closeAndJoin()
        library.mirror.closeAndJoin()
    }

    /** Waits, off the main thread, until the library is ready, or [timeoutMs] has passed (docs/library.md §1.4). */
    fun awaitReady(context: Context, timeoutMs: Long) {
        runBlocking { withTimeoutOrNull(timeoutMs) { status(context).first { it.ready } } }
    }

    /** How many syncs have changed the library in this process; null before it is opened. */
    fun revision(): Long? = opened.value?.sync?.status?.value?.revision

    /** The collation of the stored sort keys, once a sync has looked (docs/library.md §11.1). */
    fun collation(): String? = opened.value?.sync?.status?.value?.collation

    /**
     * Closes the library as the end of the process would, deleting its database when [empty], and opens it again on
     * [index], or the device's media index. Nothing syncs until [start].
     */
    @VisibleForTesting
    suspend fun reopen(context: Context, index: MediaIndex? = null, empty: Boolean = false) {
        val app = context.applicationContext
        val old = synchronized(lock) { opened.value }
        old?.let { stop(it) }
        // After the reads under way, before any new one.
        access.write {
            synchronized(lock) {
                old?.database?.close()
                if (empty) SQLiteDatabase.deleteDatabase(file(app))
                indexOverride = index
                opened.value = open(app)
            }
        }
    }

    private fun opened(context: Context): Opened = opened.value ?: synchronized(lock) {
        opened.value ?: open(context.applicationContext).also { opened.value = it }
    }

    /** What a rebuild takes from the old library database (docs/library.md §8.5). */
    private class Kept(val places: List<StoredPlace>, val libraryId: String, val lastFileId: Long)

    private fun open(context: Context, kept: Kept? = null): Opened {
        ProcessRole.current(context).requireMain("The library")
        val database = LibraryDatabase.open(context, file(context))
        if (kept != null) {
            runBlocking {
                // Together, so that a process that dies before the first sync never gives old IDs under the old library ID.
                database.withWriteTransaction {
                    database.library().setLibraryId(kept.libraryId)
                    // Not even the ID of a file that is gone goes to another one.
                    database.library().raiseLastFileId(maxOf(kept.lastFileId, kept.places.maxOfOrNull { it.fileId } ?: 0))
                }
            }
        }
        val seed = kept?.places.orEmpty()
        val sync = LibrarySync(
            database,
            indexOverride ?: MediaStoreIndex(context),
            mayRead = { mayRead(context) },
            rules = userRules(context),
            seed = seed,
        )
        val user = UserData.database(context)
        val mirror = UserMirror(
            database,
            user,
            sync,
            covers = Covers.own(context),
            // Before the first sync no file is known, and a run would write an empty mirror for nothing.
            ready = { sync.status.value.known },
        )
        val fingerprints = LibraryFingerprints(context, database, uriOf = { file ->
            val id = runBlocking { database.library().mediaStoreIdOf(file.fileId) } ?: -1L
            ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(file.place.volume), id)
        })
        val identities = IdentityAttacher(
            database,
            user,
            sync,
            // Own covers, favorites and plays are user data: their identities are never deleted (docs/library.md §8.1).
            holders = { user.user().identitiesHoldingData().toSet() },
            fingerprints = FingerprintStep(fingerprints),
            // Before the first sync every identity would look missing.
            ready = { sync.status.value.known },
            // Favorites and plays follow the keys the pass attaches (docs/library.md §8.1, §15.4).
            afterPass = mirror::request,
        )
        identities.follow(sync.status)
        return Opened(database, sync, identities, newTrackRefs(context), mirror)
    }

    /** The folder rules of the user database (docs/library.md §3.4). */
    private fun userRules(context: Context) = object : RulesSource {
        override suspend fun counter() = UserData.database(context).user().rulesCounter()

        override suspend fun rules() = UserData.database(context).user().folderRules()
            .map { FolderRule(it.volumeName, it.relativePath, it.mode == FolderRuleEntity.INCLUDE) }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, LibraryDatabase.FILE_NAME)

    private const val TAG = "Library"

    /** Whether the app may read the media index; without it the library is neither synced nor listed. */
    fun mayRead(context: Context) =
        context.checkSelfPermission(AudioPermission.name) == PackageManager.PERMISSION_GRANTED
}
