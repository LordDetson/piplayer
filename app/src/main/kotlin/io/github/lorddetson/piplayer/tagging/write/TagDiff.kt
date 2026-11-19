package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.tagging.TagArtwork
import java.text.Normalizer
import kotlinx.serialization.Serializable

@Serializable
internal data class TagChange(val before: List<String>, val after: List<String>)

internal data class TagDiff(
    val properties: Map<String, List<String>>,
    val changes: Map<String, TagChange>,
    val conflicts: Set<String> = emptySet(),
    val frontCovers: List<TagArtwork>? = null,
    val restoreId3v1: String? = null,
) {
    companion object {
        fun between(
            current: Map<String, List<String>>,
            desired: Map<String, List<String>>,
            fillEmptyOnly: Set<String> = emptySet(),
        ): TagDiff {
            val normalized = desired.filterNot { (key, _) ->
                key in fillEmptyOnly && current[key].orEmpty().any { it.isNotEmpty() }
            }.mapValues { (_, values) -> values.map { Normalizer.normalize(it, Normalizer.Form.NFC) } }
            return exact(current, normalized)
        }

        fun exact(current: Map<String, List<String>>, desired: Map<String, List<String>>): TagDiff {
            val changes = buildMap {
                for ((key, after) in desired) {
                    val before = current[key].orEmpty()
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
