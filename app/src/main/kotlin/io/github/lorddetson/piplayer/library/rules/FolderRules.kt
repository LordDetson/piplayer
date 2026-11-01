package io.github.lorddetson.piplayer.library.rules

import java.util.Locale

/** A folder the user included or excluded, with its subfolders (docs/library.md §3.4). */
data class FolderRule(val volume: String, val relativePath: String, val include: Boolean)

/** What decides about a folder: no rule above it, or the deepest rule above it. */
enum class FolderRuleState(val stored: Int) {
    NONE(0),
    INCLUDE(1),
    EXCLUDE(2),
}

/** Finds the rule that decides about a folder (docs/library.md §3.4). */
class FolderRuleMatcher(rules: List<FolderRule>) {

    /** Per volume, the rules by their path in lower case. */
    private val byVolume: Map<String, Map<String, Boolean>> =
        rules.groupBy { it.volume }.mapValues { (_, list) -> list.associate { key(it.relativePath) to it.include } }

    /** The state for [relativePath] of [volume], as the media index gives the path (empty for the root, else ending with `/`). */
    fun stateFor(volume: String, relativePath: String): FolderRuleState {
        val rules = byVolume[volume] ?: return FolderRuleState.NONE
        // The path itself, then each parent up to the root: "a/b/" is under "a/" and "", never under "a/bc/".
        var path = key(relativePath)
        while (true) {
            rules[path]?.let { return if (it) FolderRuleState.INCLUDE else FolderRuleState.EXCLUDE }
            if (path.isEmpty()) return FolderRuleState.NONE
            path = path.dropLast(1).substringBeforeLast('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it/" }
        }
    }

    private fun key(path: String) = path.lowercase(Locale.ROOT)
}
