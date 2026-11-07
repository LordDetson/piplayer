package io.github.lorddetson.piplayer.covers

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.InterruptedIOException
import kotlinx.coroutines.delay

/** Writes a cover into the pipe a controller reads (docs/library.md §13.8.4). */
object CoverPipe {

    /** What one write takes at most: a pipe that polls writable takes this much without blocking. */
    const val CHUNK = 4096

    /** How often a write that waits for its reader looks again; in between it holds no thread. */
    const val WAIT_STEP_MS = 20L

    /**
     * Writes [bytes] to the pipe [sink] in chunks, each once the pipe takes it; gives up when the reader takes nothing for
     * [idleTimeoutMs], when the whole write has taken [totalTimeoutMs], or when the reader has gone. Whether everything
     * was written. It never waits on a thread: the pipe is looked at without waiting, and between looks the write is
     * suspended, so that a reader that takes nothing cannot hold a thread that other covers need.
     */
    suspend fun write(sink: FileDescriptor, bytes: ByteArray, idleTimeoutMs: Int, totalTimeoutMs: Int = Int.MAX_VALUE): Boolean {
        val end = System.nanoTime() + totalTimeoutMs * 1_000_000L
        var offset = 0
        var idleUntil = System.nanoTime() + idleTimeoutMs * 1_000_000L
        while (offset < bytes.size) {
            when (look(sink)) {
                Pipe.GONE -> return false
                Pipe.FULL -> {
                    val now = System.nanoTime()
                    if (now >= minOf(end, idleUntil)) return false
                    delay(WAIT_STEP_MS)
                    continue
                }
                Pipe.WRITABLE -> Unit
            }
            try {
                // A chunk this size goes into a pipe that polled writable at once.
                offset += Os.write(sink, bytes, offset, minOf(CHUNK, bytes.size - offset))
                idleUntil = System.nanoTime() + idleTimeoutMs * 1_000_000L
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                // EPIPE and the like: the reader has gone.
                return false
            } catch (_: InterruptedIOException) {
                continue
            }
        }
        return true
    }

    /** Whether the reader of [sink] has closed its end already; a look that does not wait. */
    fun readerGone(sink: FileDescriptor): Boolean = look(sink) == Pipe.GONE

    private enum class Pipe { WRITABLE, FULL, GONE }

    /** What the pipe [sink] is like now; a look that does not wait. */
    private fun look(sink: FileDescriptor): Pipe {
        while (true) {
            val poll = StructPollfd().apply {
                fd = sink
                events = OsConstants.POLLOUT.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), 0)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return Pipe.GONE
            }
            if (ready == 0) return Pipe.FULL
            val events = poll.revents.toInt()
            if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return Pipe.GONE
            return if (events and OsConstants.POLLOUT != 0) Pipe.WRITABLE else Pipe.FULL
        }
    }
}
