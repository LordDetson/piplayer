package io.github.lorddetson.piplayer.library.mirror

/**
 * When a Skip lapses (TI §1): once no file of its track holds the size and modification time it stored. A changed file
 * no longer holds them, and neither does a file whose content key changed, which leaves the track's key; a copy, a move
 * and a rebuild leave the skipped file holding them. A file the tag pass has not keyed yet (as after a rebuild, or when
 * its read failed) may still be the skipped file, so while one holds the stamp the Skip stays.
 */
internal object SkipLapse {

    /**
     * Whether a Skip of the track with [trackKey] that stored [size] and [mtimeMs] lapses, given [holders]: the content
     * keys of the library's files that hold that size and modification time, null for a file without a key yet. A Skip
     * that stored no stamp lapses at once.
     */
    fun lapses(trackKey: String, size: Long?, mtimeMs: Long?, holders: Collection<String?>): Boolean {
        if (size == null || mtimeMs == null) return true
        return holders.none { it == null || it == trackKey }
    }

    /** The library's modification time of a file, kept in seconds, as a Skip stores it (TI §10.2). */
    fun mtimeMs(mtimeSeconds: Long): Long = mtimeSeconds * 1_000

    /** The library's modification time in seconds of a Skip's [mtimeMs]; null when no file can hold it. */
    fun mtimeSeconds(mtimeMs: Long): Long? = if (mtimeMs % 1_000 == 0L) mtimeMs / 1_000 else null
}
