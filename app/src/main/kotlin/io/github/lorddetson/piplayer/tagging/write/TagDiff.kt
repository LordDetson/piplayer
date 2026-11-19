package io.github.lorddetson.piplayer.tagging.write

import java.text.Normalizer

internal data class TagChange(val before: List<String>, val after: List<String>)

internal data class TagDiff(
    val properties: Map<String, List<String>>,
    val changes: Map<String, TagChange>,
    val conflicts: Set<String> = emptySet(),
) {
    companion object {
        fun between(
            current: Map<String, List<String>>,
            desired: Map<String, List<String>>,
            fillEmptyOnly: Set<String> = emptySet(),
        ): TagDiff {
            val changes = buildMap {
                for ((key, values) in desired) {
                    val before = current[key].orEmpty()
                    if (key in fillEmptyOnly && before.any { it.isNotEmpty() }) continue
                    val after = values.map { Normalizer.normalize(it, Normalizer.Form.NFC) }
                    if (before != after) put(key, TagChange(before, after))
                }
            }
            return TagDiff(applyChanges(current, changes), changes)
        }

        fun undo(current: Map<String, List<String>>, written: Map<String, TagChange>): TagDiff {
            val changes = mutableMapOf<String, TagChange>()
            val conflicts = mutableSetOf<String>()
            for ((key, change) in written) {
                if (current[key].orEmpty() == change.after) {
                    changes[key] = TagChange(change.after, change.before)
                } else {
                    conflicts += key
                }
            }
            return TagDiff(applyChanges(current, changes), changes, conflicts)
        }

        private fun applyChanges(
            current: Map<String, List<String>>,
            changes: Map<String, TagChange>,
        ): Map<String, List<String>> = current.toMutableMap().apply {
            for ((key, change) in changes) {
                if (change.after.isEmpty()) remove(key) else put(key, change.after)
            }
        }
    }
}
