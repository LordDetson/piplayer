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
     * [idleTimeoutMs] or has gone. Whether everything was written. Never blocks longer than [idleTimeoutMs] at a time.
     */
    fun write(sink: FileDescriptor, bytes: ByteArray, idleTimeoutMs: Int): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            if (!writable(sink, idleTimeoutMs)) return false
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

    /** Whether the pipe takes a chunk within [timeoutMs]; false once the reader has gone or idled that long. */
    private fun writable(sink: FileDescriptor, timeoutMs: Int): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
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
