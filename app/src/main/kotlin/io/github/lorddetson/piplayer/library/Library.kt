package io.github.lorddetson.piplayer.library

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import io.github.lorddetson.piplayer.ProcessRole
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaIndex
import io.github.lorddetson.piplayer.library.sync.MediaStoreIndex
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** The library of this process: its database and its sync, opened once, in the main process only (docs/library.md §1). */
object Library {

    private class Opened(val database: LibraryDatabase, val sync: LibrarySync)

    private val opened = MutableStateFlow<Opened?>(null)
    private val lock = Any()
    private val access = ReentrantReadWriteLock()
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
        synchronized(lock) {
            if (observer != null) return
            // Every change of the index's audio rows, on every volume; a burst brings one sync a second later.
            val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    opened.value?.sync?.requestSoon()
                }
            }
            app.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                true,
                watcher,
            )
            observer = watcher
        }
        sync.request()
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
        old?.sync?.awaitIdle()
        // After the reads under way, before any new one.
        access.write {
            synchronized(lock) {
                old?.sync?.close()
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
        val sync = LibrarySync(database, indexOverride ?: MediaStoreIndex(context), mayRead = { mayRead(context) })
        return Opened(database, sync)
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, LibraryDatabase.FILE_NAME)

    /** Whether the app may read the media index; without it the library is neither synced nor listed. */
    fun mayRead(context: Context) =
        context.checkSelfPermission(AudioPermission.name) == PackageManager.PERMISSION_GRANTED
}
