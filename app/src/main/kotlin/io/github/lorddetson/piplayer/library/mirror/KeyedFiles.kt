package io.github.lorddetson.piplayer.library.mirror

import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField

/**
 * The files whose content keys have decided values, as a whole run of the decision mirror reads them (TI §10.3): [ids]
 * ascending and each once, with their [keys]. The run compares them before it holds off the sync and reads them again
 * under the hold, where the files that [moved] in between are compared again.
 */
internal class KeyedFiles(val ids: LongArray, val keys: Array<String>) {

    init {
        require(ids.size == keys.size) { "${ids.size} files with ${keys.size} keys" }
        for (i in 1 until ids.size) require(ids[i - 1] < ids[i]) { "The files are not ascending at ${ids[i]}" }
    }

    /** The decided values of each file, in the order of [ids]; every key must be one of [byKey]. */
    fun values(byKey: Map<String, Map<DecidedField, Decided>>): List<Map<DecidedField, Decided>> = keys.map(byKey::getValue)

    /** The decided values of [fileId]; none for a file that is not one of these. */
    fun valuesOf(fileId: Long, byKey: Map<String, Map<DecidedField, Decided>>): Map<DecidedField, Decided> =
        ids.binarySearch(fileId).let { if (it < 0) emptyMap() else byKey[keys[it]].orEmpty() }

    /** The key of [fileId]; null for a file that is not one of these. */
    fun keyOf(fileId: Long): String? = ids.binarySearch(fileId).let { if (it < 0) null else keys[it] }

    /** The files that only one of these and [other] holds, or that the two hold with different keys. */
    fun moved(other: KeyedFiles): Set<Long> {
        val out = HashSet<Long>()
        var i = 0
        var j = 0
        while (i < ids.size || j < other.ids.size) {
            when {
                j == other.ids.size || (i < ids.size && ids[i] < other.ids[j]) -> out += ids[i++]
                i == ids.size || other.ids[j] < ids[i] -> out += other.ids[j++]
                else -> {
                    if (keys[i] != other.keys[j]) out += ids[i]
                    i++
                    j++
                }
            }
        }
        return out
    }
}
