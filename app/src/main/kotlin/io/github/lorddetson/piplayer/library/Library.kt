package io.github.lorddetson.piplayer.library

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.rules.RulesSource
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import io.github.lorddetson.piplayer.library.sync.MediaStoreIndex
import io.github.lorddetson.piplayer.library.tagpass.PassGate
import io.github.lorddetson.piplayer.library.tagpass.DurationWalks
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.tagpass.TagPassRunner
import io.github.lorddetson.piplayer.library.tagpass.TaggerPassReader
import io.github.lorddetson.piplayer.library.values.LegacyText
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
import kotlinx.coroutines.withTimeoutOrNull

/** The library of this process: its database and its sync, opened once, in the main process only (docs/library.md §1). */
object Library {

    private class Opened(val database: LibraryDatabase, val sync: LibrarySync) {
        @Volatile
        var pass: TagPassRunner? = null
    }

    /** When the tag pass may read; the app and the playback service tell it what they see. */
    val passGate = PassGate()

    /** Tests turn the tag pass off, because it would read the device's real library under them. */
    @Volatile
    var tagPassEnabled = true

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

    /** The status of the library's sync; it follows the library across [reopen]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun status(context: Context): Flow<LibraryStatus> {
        opened(context)
        return opened.filterNotNull().flatMapLatest { it.sync.status }
    }

    /**
     * Watches the media index and syncs now, if the app may read it; called when the main process starts and when access
     * is granted. Once the library is started, calling it again does nothing.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!mayRead(app)) return
        val sync = sync(app)
        startTagPass(app)
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
            // A change of the folder rules is applied by a sync at once.
            scope.launch {
                UserData.database(app).user().changeCounterFlow().drop(1).collect { opened.value?.sync?.request() }
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
            reader,
            cyrillicLanguage = {
                val locales = listOf(Resources.getSystem().configuration.locales, app.resources.configuration.locales)
                LegacyText.isCyrillicLanguage(locales.flatMap { list -> List(list.size()) { list[it].language } })
            },
            gate = passGate::awaitOpen,
            onBatch = SongList::applyInPlace,
        )
        val walks = DurationWalks(library.database, library.sync, reader, passGate, onBatch = SongList::applyLengths)
        return TagPassRunner(pass, library.sync.status, passGate, walks = walks)
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

    /** Waits, off the main thread, until the library is ready, or [timeoutMs] has passed (docs/library.md §1.4). */
    fun awaitReady(context: Context, timeoutMs: Long) {
        runBlocking { withTimeoutOrNull(timeoutMs) { status(context).first { it.ready } } }
    }

    /** How many syncs have changed the library in this process; null before it is opened. */
    fun revision(): Long? = opened.value?.sync?.status?.value?.revision

    /**
     * Closes the library as the end of the process would, deleting its database when [empty], and opens it again on
     * [index], or the device's media index. Nothing syncs until [start].
     */
    @VisibleForTesting
    suspend fun reopen(context: Context, index: MediaIndex? = null, empty: Boolean = false) {
        val app = context.applicationContext
        val old = synchronized(lock) { opened.value }
        old?.pass?.stopAndJoin()
        old?.sync?.awaitIdle()
        // A sync asked for after that, by a change of the index or of the user data, must not run while the database
        // closes under it.
        old?.sync?.closeAndJoin()
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

    private fun open(context: Context): Opened {
        ProcessRole.current(context).requireMain("The library")
        val database = LibraryDatabase.open(context, file(context))
        val sync = LibrarySync(
            database,
            indexOverride ?: MediaStoreIndex(context),
            mayRead = { mayRead(context) },
            rules = userRules(context),
        )
        return Opened(database, sync)
    }

    /** The folder rules of the user database (docs/library.md §3.4). */
    private fun userRules(context: Context) = object : RulesSource {
        override suspend fun counter() = UserData.database(context).user().changeCounter()

        override suspend fun rules() = UserData.database(context).user().folderRules()
            .map { FolderRule(it.volumeName, it.relativePath, it.mode == FolderRuleEntity.INCLUDE) }
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, LibraryDatabase.FILE_NAME)

    /** Whether the app may read the media index; without it the library is neither synced nor listed. */
    fun mayRead(context: Context) =
        context.checkSelfPermission(AudioPermission.name) == PackageManager.PERMISSION_GRANTED
}
