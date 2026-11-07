package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.db.FolderKey

/**
 * A node of the library tree for controllers and its media ID (docs/playback.md §10.1, §10.2). IDs do not name a node's
 * parent, so a node can move under another one (M2b's "More") and keep its ID.
 */
sealed interface TreeNode {
    val mediaId: String

    data object Root : TreeNode {
        override val mediaId: String get() = "root"
    }

    data object Songs : TreeNode {
        override val mediaId: String get() = "songs"
    }

    data object Albums : TreeNode {
        override val mediaId: String get() = "albums"
    }

    data object Artists : TreeNode {
        override val mediaId: String get() = "artists"
    }

    data object Folders : TreeNode {
        override val mediaId: String get() = "folders"
    }

    /** An album by its album key (docs/library.md §6.5). */
    data class Album(val key: String) : TreeNode {
        override val mediaId: String get() = ALBUM + MediaIdText.encode(key)
    }

    /** An artist by their artist key. */
    data class Artist(val key: String) : TreeNode {
        override val mediaId: String get() = ARTIST + MediaIdText.encode(key)
    }

    /** A folder by its volume and relative path. */
    data class Folder(val folder: FolderKey) : TreeNode {
        override val mediaId: String get() = FOLDER + MediaIdText.encode(folder.key)
    }

    /**
     * A part of a long list of [parent] (§10.2): the [part]th bucket of [section], or with an empty section the
     * [part]th run of rows.
     */
    data class Bucket(val parent: TreeNode, val section: String, val part: Int = 1) : TreeNode {
        init {
            require(parent !is Bucket && parent != Root) { "Only a list has buckets" }
            require(part >= 1) { "Parts count from 1" }
        }

        override val mediaId: String
            get() = parent.mediaId + SEPARATOR + MediaIdText.encode(section) + if (part > 1) "$SEPARATOR$part" else ""
    }

    companion object {
        private const val ALBUM = "album/"
        private const val ARTIST = "artist/"
        private const val FOLDER = "folder/"

        /** Not in the encoded form of any key or section. */
        private const val SEPARATOR = '!'

        private val FIXED = listOf(Root, Songs, Albums, Artists, Folders).associateBy { it.mediaId }

        /** The node [mediaId] names, or null when it names none; only the form [TreeNode.mediaId] gives is read. */
        fun parse(mediaId: String): TreeNode? {
            val node = parseAny(mediaId) ?: return null
            return node.takeIf { it.mediaId == mediaId }
        }

        private fun parseAny(mediaId: String): TreeNode? {
            val separator = mediaId.indexOf(SEPARATOR)
            if (separator >= 0) {
                val parent = list(mediaId.substring(0, separator)) ?: return null
                val rest = mediaId.substring(separator + 1)
                val partAt = rest.indexOf(SEPARATOR)
                val section = MediaIdText.decode(if (partAt < 0) rest else rest.substring(0, partAt)) ?: return null
                val part = if (partAt < 0) 1 else rest.substring(partAt + 1).toIntOrNull()?.takeIf { it >= 2 } ?: return null
                return Bucket(parent, section, part)
            }
            return FIXED[mediaId] ?: list(mediaId)
        }

        /** A node that can have buckets. */
        private fun list(id: String): TreeNode? {
            FIXED[id]?.let { return it.takeIf { node -> node != Root } }
            fun key(prefix: String) = if (id.startsWith(prefix)) MediaIdText.decode(id.substring(prefix.length))?.takeIf { it.isNotEmpty() } else null
            key(ALBUM)?.let { return Album(it) }
            key(ARTIST)?.let { return Artist(it) }
            key(FOLDER)?.let { return FolderKey.parse(it)?.let(::Folder) }
            return null
        }
    }
}
