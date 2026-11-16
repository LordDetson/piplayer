package io.github.lorddetson.piplayer.library.mirror

/**
 * When a Skip lapses (TI §1): once no file of its track holds the size and modification time it stored. A changed file
 * no longer holds them, and neither does a file whose content key changed, which leaves the track's key; a copy, a move
 * and a rebuild leave the skipped file holding them.
 */
internal object SkipLapse {

    /** A Skip that counts: its decision, its track and the stamp of the file it was made on, the time in milliseconds. */
    data class Skip(val decisionId: Long, val identityId: Long, val size: Long?, val mtimeMs: Long?)

    /** A file of the track as the library stores it, the modification time in seconds. */
    data class FileStamp(val size: Long, val mtimeSeconds: Long)

    /** Whether [skip] lapses with [files], the files its track holds now. A Skip that stored no stamp lapses at once. */
    fun lapses(skip: Skip, files: Collection<FileStamp>): Boolean {
        val size = skip.size ?: return true
        val mtimeMs = skip.mtimeMs ?: return true
        return files.none { it.size == size && mtimeMs(it.mtimeSeconds) == mtimeMs }
    }

    /** The library's modification time of a file, kept in seconds, as a Skip stores it (TI §10.2). */
    fun mtimeMs(mtimeSeconds: Long): Long = mtimeSeconds * 1_000
}
