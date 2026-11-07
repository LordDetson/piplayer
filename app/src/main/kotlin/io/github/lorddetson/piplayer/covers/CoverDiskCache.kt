package io.github.lorddetson.piplayer.covers

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the disk cache holds for a key: a picture's bytes, or that its source had none. */
sealed interface Cached {
    class Picture(val bytes: ByteArray) : Cached

    data object Nothing : Cached
}

/**
 * The covers' disk cache (docs/library.md §13.3): one file per key in [dir], at most [maxBytes] in all, the least recently
 * used going first. An empty file says that the source had no picture. Each file counts at least [MIN_FILE_BYTES], the
 * room a file takes of its own. Used in one process only; the calls block.
 */
class CoverDiskCache(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    /** When a file was last used; read once per file when the cache opens. */
    private val modifiedAt: (File) -> Long = File::lastModified,
) {

    /** File name to what it counts, least recently used first; read from the folder at the first call. */
    @Volatile
    private var index: LinkedHashMap<String, Long>? = null
    private var total = 0L

    /** Held while the folder is read, so that one thread reads it while the others wait without holding the cache. */
    private val scanning = Any()

    fun read(key: String): Cached? {
        opened()
        return readNow(key)
    }

    @Synchronized
    private fun readNow(key: String): Cached? {
        val files = files()
        val name = nameOf(key)
        if (name !in files) return null
        val file = File(dir, name)
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            // Gone behind the cache's back: the app's caches were cleared.
            forget(name)
            return null
        }
        files[name] = files.remove(name)!!
        file.setLastModified(clock())
        return if (bytes.isEmpty()) Cached.Nothing else Cached.Picture(bytes)
    }

    /** Stores [bytes] for [key], or that its source had none when they are null. */
    fun write(key: String, bytes: ByteArray?) {
        opened()
        writeNow(key, bytes)
    }

    @Synchronized
    private fun writeNow(key: String, bytes: ByteArray?) {
        val files = files()
        val name = nameOf(key)
        forget(name)
        val weight = weightOf((bytes?.size ?: 0).toLong())
        if (weight > maxBytes) return
        dir.mkdirs()
        val temporary = File(dir, "$name.tmp")
        val file = File(dir, name)
        try {
            temporary.writeBytes(bytes ?: ByteArray(0))
            if (!temporary.renameTo(file)) throw IOException("Could not rename $temporary")
        } catch (_: IOException) {
            temporary.delete()
            return
        }
        file.setLastModified(clock())
        files[name] = weight
        total += weight
        trim(files)
    }

    /** Deletes every file. */
    @Synchronized
    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        index = LinkedHashMap(64, 0.75f, true)
        total = 0
    }

    /** What the files count in all. */
    val size: Long
        get() {
            opened()
            return synchronized(this) { total }
        }

    private fun forget(name: String) {
        val files = files()
        files.remove(name)?.let { total -= it }
        File(dir, name).delete()
    }

    private fun trim(files: LinkedHashMap<String, Long>) {
        val iterator = files.entries.iterator()
        while (total > maxBytes && iterator.hasNext()) {
            val (name, weight) = iterator.next()
            iterator.remove()
            total -= weight
            File(dir, name).delete()
        }
    }

    private fun files(): LinkedHashMap<String, Long> = checkNotNull(index) { "The cache is not open" }

    /**
     * Reads the folder once (docs/library.md §13.3): each file's time and length once, sorted outside the cache's lock, so
     * that the first covers of a cold start do not wait behind a scan of thousands of files.
     */
    private fun opened() {
        if (index != null) return
        synchronized(scanning) {
            if (index != null) return
            val found = dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
                .map { Triple(it.name, weightOf(it.length()), modifiedAt(it)) }
                .sortedBy { it.third }
            synchronized(this) {
                // No write runs now (each holds the lock): a temporary file left is one whose write died before its rename.
                dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
                val files = LinkedHashMap<String, Long>(maxOf(64, found.size * 2), 0.75f, true)
                for ((name, weight) in found) {
                    files[name] = weight
                    total += weight
                }
                index = files
                trim(files)
            }
        }
    }

    private fun weightOf(length: Long) = maxOf(length, MIN_FILE_BYTES)

    /** A safe file name for any key: its SHA-1 in hex. */
    private fun nameOf(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        const val MIN_FILE_BYTES = 512L
    }
}
