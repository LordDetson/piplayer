package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField

/**
 * The comparison of a whole run of the decision mirror (TI §10.3): the values each keyed file should hold against the
 * rows of `decided_mirror`, which [row] takes one by one in any order. A run that finds every value mirrored builds no
 * row and no map of rows: with a decision on every one of 20 000 files, materialising both sides took most of the time
 * the run held off the sync. Rows may also be read in [part]s on several threads, a part per thread.
 *
 * [fileIds] are the files that should hold decided values, ascending and each once, and [desired] their values in the
 * same order. A file differs when one of its rows is missing, has another value, level or decision, or names a part this
 * build does not know, and when it holds rows but should hold none.
 */
internal class DecidedComparison(private val fileIds: LongArray, private val desired: List<Map<DecidedField, Decided>>) {

    init {
        require(fileIds.size == desired.size) { "${fileIds.size} files with ${desired.size} sets of values" }
        for (i in 1 until fileIds.size) require(fileIds[i - 1] < fileIds[i]) { "The files are not ascending at ${fileIds[i]}" }
    }

    private val parts = ArrayList<Part>()
    private val own by lazy { part() }

    /** Rows read on one thread; parts of one comparison may read at the same time. */
    inner class Part internal constructor() {
        /** The rows of each file of [fileIds] that hold its desired value. */
        internal val matching = IntArray(fileIds.size)

        /** Whether a row of each file of [fileIds] did not hold its desired value. */
        internal val differs = BooleanArray(fileIds.size)

        /** The files that hold rows but should hold none. */
        internal val stray = HashSet<Long>()

        /** One stored row of `decided_mirror`; [value] null is an empty value. */
        fun row(fileId: Long, field: String, value: String?, level: Int, decisionId: Long) {
            val i = fileIds.binarySearch(fileId)
            if (i < 0) {
                stray += fileId
                return
            }
            if (differs[i]) return
            // Each part of a file has one row (the primary key), so a file matches once as many rows match as it has parts.
            val wanted = DecidedField.ofName(field)?.let { desired[i][it] }
            if (wanted != null && wanted.level == level && wanted.decisionId == decisionId && wanted.value == value) {
                matching[i]++
            } else {
                differs[i] = true
            }
        }
    }

    /** A new part that reads rows of its own. */
    fun part(): Part = Part().also { synchronized(parts) { parts += it } }

    /** One stored row of `decided_mirror`, read on one thread; [value] null is an empty value. */
    fun row(fileId: Long, field: String, value: String?, level: Int, decisionId: Long) = own.row(fileId, field, value, level, decisionId)

    /** The files whose rows differ from their desired values, ascending; once every part has read its rows. */
    fun differing(): List<Long> {
        val all = synchronized(parts) { parts.toList() }
        val out = ArrayList<Long>()
        for (i in fileIds.indices) {
            if (all.any { it.differs[i] } || all.sumOf { it.matching[i] } != desired[i].size) out += fileIds[i]
        }
        val stray = all.flatMapTo(HashSet()) { it.stray }
        if (stray.isNotEmpty()) {
            out += stray
            out.sort()
        }
        return out
    }
}
