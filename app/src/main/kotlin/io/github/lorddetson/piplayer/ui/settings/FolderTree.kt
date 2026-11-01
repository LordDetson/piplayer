package io.github.lorddetson.piplayer.ui.settings

/** A folder of a volume in the Library folders tree (docs/library.md §3.6). */
data class FolderNode(val name: String, val relativePath: String, val children: List<FolderNode>)

/** Builds the tree of a volume's folders that hold audio, with their parents. */
object FolderTree {

    /** [paths] are relative paths as the media index gives them, each ending with `/`. */
    fun of(paths: Collection<String>): List<FolderNode> {
        // Every folder with its parents, as paths ending with "/".
        val all = sortedSetOf<String>()
        for (path in paths) {
            var folder = path
            while (folder.isNotEmpty()) {
                all += folder
                folder = folder.dropLast(1).substringBeforeLast('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it/" }
            }
        }
        return children("", all)
    }

    private fun children(parent: String, all: Set<String>): List<FolderNode> = all
        .filter { it.startsWith(parent) && it != parent && it.removePrefix(parent).count { c -> c == '/' } == 1 }
        .map { path -> FolderNode(path.removePrefix(parent).dropLast(1), path, children(path, all)) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
