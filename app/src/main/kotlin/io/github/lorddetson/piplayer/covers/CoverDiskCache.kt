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
) {

    /** File name to what it counts, least recently used first; read from the folder at the first call. */
    private var index: LinkedHashMap<String, Long>? = null
    private var total = 0L

    @Synchronized
    fun read(key: String): Cached? {
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
    @Synchronized
    fun write(key: String, bytes: ByteArray?) {
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

    /** What the files count in all. */
    val size: Long
        @Synchronized get() {
            files()
            return total
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

    private fun files(): LinkedHashMap<String, Long> = index ?: LinkedHashMap<String, Long>(64, 0.75f, true).also { files ->
        val found = dir.listFiles().orEmpty().filter { it.isFile }
        // A write that died before its rename.
        found.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        for (file in found.filterNot { it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }) {
            val weight = weightOf(file.length())
            files[file.name] = weight
            total += weight
        }
        index = files
        trim(files)
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
