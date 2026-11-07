package io.github.lorddetson.piplayer.covers

import java.io.File

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

    fun read(key: String): Cached? = TODO()

    /** Stores [bytes] for [key], or that its source had none when they are null. */
    fun write(key: String, bytes: ByteArray?): Unit = TODO()

    /** What the files count in all. */
    val size: Long get() = TODO()

    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        const val MIN_FILE_BYTES = 512L
    }
}
