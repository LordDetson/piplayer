package io.github.lorddetson.piplayer.tagging.write

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal val playbackTagWriteGate = TagWriteGate()

/** Keeps a tag write exclusive with reads of the same URI, including playback that starts during a write. */
internal class TagWriteGate {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private val readers = HashMap<String, Int>()
    private val writers = HashSet<String>()
    private var reserved = emptySet<String>()

    fun reservePlayback(current: String?, next: String?) = lock.withLock {
        reserved = setOfNotNull(current, next)
    }

    /** Called on the playback loader thread before opening a descriptor. */
    fun beginRead(uri: String): Closeable = lock.withLock {
        while (uri in writers) released.await()
        readers[uri] = readers.getOrDefault(uri, 0) + 1
        lease {
            val count = readers.getValue(uri)
            if (count == 1) readers.remove(uri) else readers[uri] = count - 1
        }
    }

    fun tryWrite(uri: String): Closeable? = lock.withLock {
        if (uri in reserved || uri in readers || !writers.add(uri)) return@withLock null
        lease {
            writers.remove(uri)
            released.signalAll()
        }
    }

    private fun lease(release: () -> Unit): Closeable = object : Closeable {
        private var closed = false

        override fun close() = lock.withLock {
            if (!closed) {
                closed = true
                release()
            }
        }
    }
}
