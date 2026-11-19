package io.github.lorddetson.piplayer.tagging.write

import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal val playbackTagWriteGate = TagWriteGate()

/** Keeps a tag write exclusive with playback of the same file, including playback that starts during a write. */
internal class TagWriteGate {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private val availability = MutableStateFlow(0L)
    private val readers = HashMap<String, Int>()
    private val writers = HashSet<String>()
    private var reserved = emptySet<String>()

    fun reservePlayback(current: String?, next: String?) = lock.withLock {
        val updated = setOfNotNull(current?.let(::keyOf), next?.let(::keyOf))
        val mayRelease = reserved.any { it !in updated }
        reserved = updated
        if (mayRelease) availability.value++
    }

    /** Called on the playback loader thread before opening a descriptor. */
    fun beginRead(uri: String): Closeable = lock.withLock {
        val key = keyOf(uri)
        while (key in writers) released.await()
        readers[key] = readers.getOrDefault(key, 0) + 1
        lease {
            val count = readers.getValue(key)
            if (count == 1) {
                readers.remove(key)
                availability.value++
            } else {
                readers[key] = count - 1
            }
        }
    }

    fun tryWrite(uri: String): Closeable? = lock.withLock {
        val key = keyOf(uri)
        if (!canWrite(key)) return@withLock null
        writers.add(key)
        lease {
            writers.remove(key)
            released.signalAll()
            availability.value++
        }
    }

    suspend fun awaitAnyWritable(uris: Set<String>) {
        require(uris.isNotEmpty())
        val keys = uris.map(::keyOf)
        availability.first { lock.withLock { keys.any(::canWrite) } }
    }

    private fun canWrite(key: String) = key !in reserved && key !in readers && key !in writers

    // MediaStore uses one external database for shared storage. Playback also sees its synthetic collection URI.
    private fun keyOf(uri: String): String {
        val (volume, row) = MEDIA_STORE_AUDIO.matchEntire(uri)?.destructured ?: return uri
        val id = row.toLongOrNull() ?: return uri
        val collection = if (volume == "internal") volume else "external"
        return "content://media/$collection/audio/media/$id"
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

    private companion object {
        val MEDIA_STORE_AUDIO = Regex("""content://media/([a-zA-Z0-9_-]+)/audio/media/([0-9]+)(?:[?#].*)?""")
    }
}
