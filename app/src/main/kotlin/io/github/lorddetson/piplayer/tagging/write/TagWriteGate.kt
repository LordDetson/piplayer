package io.github.lorddetson.piplayer.tagging.write

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal val playbackTagWriteGate = TagWriteGate()

/** Keeps a tag write exclusive with reads of the same URI, including playback that starts during a write. */
internal class TagWriteGate {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private val availability = MutableStateFlow(0L)
    private val readers = HashMap<String, Int>()
    private val writers = HashSet<String>()
    private var reserved = emptySet<String>()

    fun reservePlayback(current: String?, next: String?) = lock.withLock {
        val updated = setOfNotNull(current, next)
        val mayRelease = reserved.any { it !in updated }
        reserved = updated
        if (mayRelease) availability.value++
    }

    /** Called on the playback loader thread before opening a descriptor. */
    fun beginRead(uri: String): Closeable = lock.withLock {
        while (uri in writers) released.await()
        readers[uri] = readers.getOrDefault(uri, 0) + 1
        lease {
            val count = readers.getValue(uri)
            if (count == 1) {
                readers.remove(uri)
                availability.value++
            } else {
                readers[uri] = count - 1
            }
        }
    }

    fun tryWrite(uri: String): Closeable? = lock.withLock {
        if (!canWrite(uri)) return@withLock null
        writers.add(uri)
        lease {
            writers.remove(uri)
            released.signalAll()
            availability.value++
        }
    }

    suspend fun awaitAnyWritable(uris: Set<String>) {
        require(uris.isNotEmpty())
        availability.first { lock.withLock { uris.any(::canWrite) } }
    }

    private fun canWrite(uri: String) = uri !in reserved && uri !in readers && uri !in writers

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
