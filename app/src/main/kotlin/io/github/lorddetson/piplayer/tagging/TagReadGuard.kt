package io.github.lorddetson.piplayer.tagging

import android.os.Process
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The file a native call reads, for the count of failed reads (docs/library.md §4.3, §4.4). */
sealed interface TagTarget {

    /** A file of the library, by its row ID in `audio_file`. */
    data class LibraryFile(val fileId: Long) : TagTarget

    /** Any other file; another size or modification time makes it a new file. */
    data class Outside(val uri: String, val size: Long, val mtimeMs: Long) : TagTarget
}

/** The count of failed reads per file; every change is committed when the call returns. */
interface UnsafeCounts {
    suspend fun count(target: TagTarget): Int

    suspend fun raise(target: TagTarget)

    suspend fun reset(target: TagTarget)
}

/** Thrown by a call during which `:tagger` died. */
class TaggerDiedException(cause: Throwable? = null) : Exception("The tag reader process died", cause)

sealed interface TaggerResult<out T> {
    data class Replied<T>(val value: T) : TaggerResult<T>

    /** `:tagger` died during the call. */
    data object Crashed : TaggerResult<Nothing>

    /** The call took longer than the timeout, and `:tagger` was killed. */
    data object TimedOut : TaggerResult<Nothing>

    /** The file has failed [TagReadGuard.UNSAFE_COUNT] times; it was not read. */
    data object Unsafe : TaggerResult<Nothing>

    /** `:tagger` could not be reached, so nothing was read; it says nothing about the file. */
    data object Unavailable : TaggerResult<Nothing>
}

/**
 * Runs native calls one at a time, each counted against its file before it starts and cleared when it returns
 * (docs/library.md §4.3). A call still running after [timeout] is ended by [run]'s `kill`.
 */
class TagReadGuard(private val counts: UnsafeCounts, private val timeout: Duration = DEFAULT_TIMEOUT) {

    private val mutex = Mutex()

    /** Whether [target] has failed [UNSAFE_COUNT] times; [run] would not call for it. */
    suspend fun isUnsafe(target: TagTarget): Boolean = counts.count(target) >= UNSAFE_COUNT

    /**
     * Runs [call], which blocks and throws [TaggerDiedException] when `:tagger` dies, for [target]; [kill] ends
     * `:tagger` when the call takes too long or its caller gives up.
     */
    suspend fun <T> run(target: TagTarget, kill: () -> Unit, call: () -> T): TaggerResult<T> = mutex.withLock {
        if (isUnsafe(target)) return TaggerResult.Unsafe
        counts.raise(target)
        // On a thread of its own: a call that never returns must not hold up the caller or the next call.
        val reply = CompletableDeferred<Result<T>>()
        calls.execute { reply.complete(runCatching(call)) }
        val result = try {
            withTimeoutOrNull(timeout) { reply.await() }
        } catch (e: CancellationException) {
            // No native call outlives its caller; it proved nothing about the file (docs/library.md §4.3).
            withContext(NonCancellable) {
                kill()
                counts.reset(target)
            }
            throw e
        }
        if (result == null) {
            kill()
            // Once its process is gone, a binder call returns at once.
            withTimeoutOrNull(KILL_GRACE) { reply.await() }
            return TaggerResult.TimedOut
        }
        val value = result.getOrElse { e -> if (e is TaggerDiedException) return TaggerResult.Crashed else throw e }
        counts.reset(target)
        TaggerResult.Replied(value)
    }

    /**
     * [run] with the call at background priority, which a binder call carries over to `:tagger`, and back afterwards: the
     * threads are shared with calls of normal priority.
     */
    suspend fun <T> run(target: TagTarget, kill: () -> Unit, background: Boolean, call: () -> T): TaggerResult<T> {
        if (!background) return run(target, kill, call)
        return run(target, kill) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                call()
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    companion object {
        val DEFAULT_TIMEOUT = 30.seconds

        /** Failures after which a file is not read any more. */
        const val UNSAFE_COUNT = 2

        private val KILL_GRACE = 5.seconds

        private val calls = Executors.newCachedThreadPool { runnable -> Thread(runnable, "tagger-call") }
    }
}
