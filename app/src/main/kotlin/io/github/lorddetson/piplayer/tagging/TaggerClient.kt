package io.github.lorddetson.piplayer.tagging

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import io.github.lorddetson.piplayer.library.Library
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A reply of `:tagger`; [viaFile] tells whether it crossed through a file (docs/library.md §4.2). */
data class TagRead(val report: TagReport, val viaFile: Boolean)

/**
 * Calls `:tagger` from the main process, one call at a time, counting each against its file (docs/library.md §4.3).
 * Binds the service on the first call and unbinds after [idle] without calls.
 */
class TaggerClient(
    context: Context,
    counts: UnsafeCounts,
    timeout: Duration = TagReadGuard.DEFAULT_TIMEOUT,
    private val idle: Duration = IDLE,
) {

    private val app = context.applicationContext
    private val guard = TagReadGuard(counts, timeout)
    private val spillDir = spillDir(app)
    private val handler = Handler(Looper.getMainLooper())
    private val unbindWhenIdle = Runnable { close() }
    private val lock = Any()
    private var connection: Connection? = null
    private var cleaned = false

    /** One binding; Android connects it again after `:tagger` dies, until it gives up on the binding. */
    private inner class Connection : ServiceConnection {
        @Volatile
        var service = CompletableDeferred<ITagger>()

        @Volatile
        var died = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val tagger = ITagger.Stub.asInterface(binder)
            if (!service.complete(tagger)) service = CompletableDeferred(tagger)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (service.isCompleted) service = CompletableDeferred()
        }

        override fun onBindingDied(name: ComponentName) {
            died = true
        }

        override fun onNullBinding(name: ComponentName) {
            died = true
        }
    }

    /** Whether the client holds a binding to the service now. */
    val isBound: Boolean get() = synchronized(lock) { connection != null }

    /**
     * Reads the tags of [file], which stays open and the caller's, for [target]. With [markLegacy] the caller decodes the
     * text in legacy encodings (`LegacyText`).
     */
    suspend fun read(
        target: TagTarget,
        file: ParcelFileDescriptor,
        fileName: String?,
        markLegacy: Boolean = false,
    ): TaggerResult<TagRead> {
        // Not even a binding for an unsafe file.
        if (guard.isUnsafe(target)) return TaggerResult.Unsafe
        handler.removeCallbacks(unbindWhenIdle)
        try {
            val (tagger, pid) = connected()
            return guard.run(target, kill = { Process.killProcess(pid) }) {
                val reply = try {
                    tagger.read(file, fileName, markLegacy)
                } catch (e: DeadObjectException) {
                    throw TaggerDiedException(e)
                }
                TagRead(TagReplies.unpack(reply.body, spillDir), viaFile = reply.body is TagReplyBody.InFile)
            }
        } finally {
            handler.postDelayed(unbindWhenIdle, idle.inWholeMilliseconds)
        }
    }

    /** Unbinds now; a later call binds again. */
    fun close() {
        handler.removeCallbacks(unbindWhenIdle)
        synchronized(lock) {
            connection?.let(app::unbindService)
            connection = null
        }
    }

    /** The service and its process ID; once more if `:tagger` died just before. */
    private suspend fun connected(): Pair<ITagger, Int> {
        val tagger = service()
        return try {
            tagger to withContext(Dispatchers.IO) { tagger.pid() }
        } catch (_: DeadObjectException) {
            val again = service()
            again to withContext(Dispatchers.IO) { again.pid() }
        }
    }

    /** The live service, bound anew when there is no binding or Android gave up on it. */
    private suspend fun service(): ITagger {
        val current = synchronized(lock) {
            connection?.takeIf { it.died }?.let {
                app.unbindService(it)
                connection = null
            }
            connection ?: bind()
        }
        val tagger = withTimeout(BIND_TIMEOUT) { current.service.await() }
        if (tagger.asBinder().isBinderAlive) return tagger
        // Dead and not yet reported: wait for Android to connect the binding again.
        current.service = CompletableDeferred()
        return withTimeout(BIND_TIMEOUT) { current.service.await() }
    }

    private fun bind(): Connection {
        if (!cleaned) {
            // Replies left by a process that died before the main process read them.
            spillDir.listFiles()?.forEach(File::delete)
            cleaned = true
        }
        val fresh = Connection()
        check(app.bindService(Intent(app, TaggerService::class.java), fresh, Context.BIND_AUTO_CREATE)) {
            "Cannot bind the tag reader service"
        }
        connection = fresh
        return fresh
    }

    companion object {
        val IDLE = 30.seconds
        private val BIND_TIMEOUT = 10.seconds

        /** Where `:tagger` writes replies too large to cross inline. */
        fun spillDir(context: Context) = File(context.cacheDir, "tagger")
    }
}

/** A file outside the library, named by [uri], with the size and modification time of the open [file]. */
fun outsideTarget(uri: String, file: ParcelFileDescriptor): TagTarget.Outside {
    val stat = Os.fstat(file.fileDescriptor)
    return TagTarget.Outside(uri, stat.st_size, stat.st_mtim.tv_sec * 1_000 + stat.st_mtim.tv_nsec / 1_000_000)
}

/** The main process's client, counting in the library database. */
object Tagger {

    // The client holds the application context only, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var client: TaggerClient? = null

    fun client(context: Context): TaggerClient = client ?: synchronized(this) {
        client ?: TaggerClient(context, LibraryUnsafeCounts(Library.database(context))).also { client = it }
    }
}
