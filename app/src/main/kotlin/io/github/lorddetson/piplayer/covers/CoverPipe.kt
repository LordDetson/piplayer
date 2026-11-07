package io.github.lorddetson.piplayer.covers

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.InterruptedIOException

/** Writes a cover into the pipe a controller reads (docs/library.md §13.8.4). */
object CoverPipe {

    /** What one write takes at most: a pipe that polls writable takes this much without blocking. */
    const val CHUNK = 4096

    /**
     * Writes [bytes] to the pipe [sink] in chunks, each once the pipe takes it; gives up when the reader takes nothing for
     * [idleTimeoutMs], when the whole write has taken [totalTimeoutMs], or when the reader has gone. Whether everything
     * was written. Never blocks longer than [idleTimeoutMs] at a time.
     */
    fun write(sink: FileDescriptor, bytes: ByteArray, idleTimeoutMs: Int, totalTimeoutMs: Int = Int.MAX_VALUE): Boolean {
        val end = System.nanoTime() + totalTimeoutMs * 1_000_000L
        var offset = 0
        while (offset < bytes.size) {
            val deadline = minOf(end, System.nanoTime() + idleTimeoutMs * 1_000_000L)
            if (!writable(sink, deadline)) return false
            try {
                offset += Os.write(sink, bytes, offset, minOf(CHUNK, bytes.size - offset))
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
    fun readerGone(sink: FileDescriptor): Boolean {
        val poll = StructPollfd().apply {
            fd = sink
            events = OsConstants.POLLOUT.toShort()
        }
        val ready = try {
            Os.poll(arrayOf(poll), 0)
        } catch (_: ErrnoException) {
            return false
        }
        return ready > 0 && poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0
    }

    /** Whether the pipe takes a chunk before [deadline] (`System.nanoTime`); false once the reader has gone. */
    private fun writable(sink: FileDescriptor, deadline: Long): Boolean {
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (left <= 0) return false
            val poll = StructPollfd().apply {
                fd = sink
                events = OsConstants.POLLOUT.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), left)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return false
            }
            if (ready == 0) return false
            val events = poll.revents.toInt()
            if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return false
            if (events and OsConstants.POLLOUT != 0) return true
        }
    }
}
