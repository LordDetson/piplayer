package io.github.lorddetson.piplayer.library.mirror

/**
 * The files that each run of the decision mirror changed, by the version the run raised (TI §7.1), so that the session
 * compares only the queue items of those files. The last [keptRuns] runs are kept, each with at most [maxFiles] files; a
 * run that changed more, as a whole run after a rebuild, is kept as one whose files are not known.
 */
internal class DecidedChanges(private val keptRuns: Int = KEPT_RUNS, private val maxFiles: Int = MAX_FILES) {

    /** By version, oldest first; null for a run whose files are not kept. */
    private val runs = LinkedHashMap<Long, Set<Long>?>()

    /** The run that raised the version to [version] changed [files]. */
    @Synchronized
    fun record(version: Long, files: Collection<Long>) {
        runs[version] = if (files.size > maxFiles) null else files.toHashSet()
        while (runs.size > keptRuns) runs.remove(runs.keys.first())
    }

    /**
     * The files that the runs after version [seen] up to [upTo] changed, or null when that is not known: a run among them
     * is no longer kept or changed too many files, or the version went back (a new library's mirror counts from 0 again).
     */
    @Synchronized
    fun after(seen: Long, upTo: Long): Set<Long>? {
        if (upTo < seen) return null
        val files = HashSet<Long>()
        for (version in seen + 1..upTo) files += runs[version] ?: return null
        return files
    }

    companion object {
        const val KEPT_RUNS = 16
        const val MAX_FILES = 1_000
    }
}
