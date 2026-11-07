package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.search.SearchTerms
import io.github.lorddetson.piplayer.library.sort.Sections
import io.github.lorddetson.piplayer.library.sort.SortKey
import io.github.lorddetson.piplayer.library.sort.SortKeys

/** A folder with the number of visible songs right in it. */
data class FolderSongs(val volume: String, val relativePath: String, val songs: Int)

/** A folder as a level lists it: [songs] counts those in its subfolders too; a volume's root has the volume as [name]. */
data class FolderEntry(val volume: String, val relativePath: String, val name: String, val songs: Int, val section: String? = null)

/** The top of the Folders tab: one volume's root ([folder]), or the volumes themselves when there are more. */
data class TopLevel(val volumeLevel: Boolean, val folder: Pair<String, String>?, val entries: List<FolderEntry>)

/**
 * The folder levels of the library, from the folders that hold visible songs (docs/library.md §6.1, §6.4). Paths are
 * relative paths as the library keeps them: empty for a volume's root, otherwise ending with `/`. A folder exists while
 * any visible song lies in it or below it, so parents without songs of their own are levels too.
 */
class FolderLevels(folders: List<FolderSongs>) {

    private class Node(val volume: String, val path: String) {
        var own = 0
        var total = 0
        val children = HashMap<String, Node>()
        val name: String = path.dropLast(1).substringAfterLast('/')

        /** Normalized once: levels search by it on every read. */
        val searchKey: String by lazy(LazyThreadSafetyMode.NONE) { TextNormalizer.normalize(name) }
        val glued: String by lazy(LazyThreadSafetyMode.NONE) { searchKey.replace(" ", "") }

        /** The collator's key of the name, which levels sort by (docs/library.md §11.1). */
        val sortKey: SortKey by lazy(LazyThreadSafetyMode.NONE) { SortKeys.of(name) }
        val sorted: List<Node> by lazy(LazyThreadSafetyMode.NONE) {
            children.values.sortedWith(compareBy<Node> { it.sortKey }.thenBy { it.path })
        }
    }

    private val roots = HashMap<String, Node>()

    init {
        for (folder in folders) {
            if (folder.songs <= 0) continue
            val root = roots.getOrPut(folder.volume) { Node(folder.volume, "") }
            root.total += folder.songs
            var node = root
            var start = 0
            val path = folder.relativePath
            while (start < path.length) {
                val end = path.indexOf('/', start).let { if (it < 0) path.length else it + 1 }
                val childPath = path.substring(0, end)
                node = node.children.getOrPut(childPath) { Node(folder.volume, childPath) }
                node.total += folder.songs
                start = end
            }
            node.own += folder.songs
        }
    }

    /** The volumes with visible songs, the primary one first. */
    val volumes: List<String> get() = roots.keys.sortedWith(compareBy<String> { it != PRIMARY }.thenBy { it })

    fun top(): TopLevel {
        val volumes = volumes
        if (volumes.size == 1) {
            val volume = volumes.single()
            return TopLevel(volumeLevel = false, folder = volume to "", entries = children(volume, ""))
        }
        return TopLevel(
            volumeLevel = true,
            folder = null,
            entries = volumes.map { FolderEntry(it, "", it, roots.getValue(it).total) },
        )
    }

    /** The subfolders of a folder by name, or none for one that does not exist. */
    fun children(volume: String, relativePath: String, reversed: Boolean = false): List<FolderEntry> {
        val node = find(volume, relativePath) ?: return emptyList()
        val sorted = sortedChildren(node).map { it.entry() }
        return if (reversed) sorted.reversed() else sorted
    }

    fun contains(volume: String, relativePath: String): Boolean = find(volume, relativePath) != null

    /** The visible songs right in a folder. */
    fun ownSongs(volume: String, relativePath: String): Int = find(volume, relativePath)?.own ?: 0

    /** The folders of a tree in the order it plays: each folder, then each subfolder in name order, depth first. */
    fun treeOrder(volume: String, relativePath: String): List<String> {
        val node = find(volume, relativePath) ?: return emptyList()
        val order = ArrayList<String>()
        fun walk(node: Node) {
            order += node.path
            sortedChildren(node).forEach(::walk)
        }
        walk(node)
        return order
    }

    /** Every folder whose name holds all the words of [query], in path order. */
    fun search(query: String): List<FolderEntry> {
        val terms = SearchTerms.of(query)
        if (terms.isEmpty()) return emptyList()
        val found = ArrayList<FolderEntry>()
        fun walk(node: Node) {
            for (child in sortedChildren(node)) {
                if (terms.all { child.searchKey.contains(it) || child.glued.contains(it) }) found += child.entry()
                walk(child)
            }
        }
        volumes.forEach { walk(roots.getValue(it)) }
        return found
    }

    private fun find(volume: String, relativePath: String): Node? {
        var node = roots[volume] ?: return null
        if (relativePath.isEmpty()) return node
        var start = 0
        while (start < relativePath.length) {
            val end = relativePath.indexOf('/', start).let { if (it < 0) return null else it + 1 }
            node = node.children[relativePath.substring(0, end)] ?: return null
            start = end
        }
        return node
    }

    private fun sortedChildren(node: Node): List<Node> = node.sorted

    private fun Node.entry() = FolderEntry(volume, path, name, total, Sections.letter(sortKey, buckets))

    private val buckets by lazy(LazyThreadSafetyMode.NONE) { SortKeys.current().buckets }

    companion object {
        const val PRIMARY = "external_primary"
    }
}
