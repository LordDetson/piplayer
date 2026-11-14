package io.github.lorddetson.piplayer.library.mirror

/** Where the references of an album go when its key changes (docs/library.md §15.3): they follow its tracks. */
object AlbumRelink {

    /**
     * The moves, old key to new key, of the [referenced] album keys. [present] are the keys that name an album now,
     * [members] the identities seen on each referenced key, and [albumsOf] the album keys of the files that hold each
     * identity's key now (no entry for an identity no file holds).
     */
    fun moves(
        referenced: Set<String>,
        present: Set<String>,
        members: Map<String, Set<Long>>,
        albumsOf: Map<Long, Set<String>>,
    ): Map<String, String> {
        val moves = LinkedHashMap<String, String>()
        for (key in referenced) {
            // A key that still names an album keeps its references, also when some of its tracks left it.
            if (key in present) continue
            val counts = HashMap<String, Int>()
            var presentMembers = 0
            for (member in members[key].orEmpty()) {
                val albums = albumsOf[member]?.takeIf { it.isNotEmpty() } ?: continue
                presentMembers++
                for (album in albums) counts.merge(album, 1, Int::plus)
            }
            // More than half; copies in two albums could give two keys that, and then they tie as any other tie does.
            val majority = counts.entries.filter { it.value * 2 > presentMembers }.sortedByDescending { it.value }
            val target = majority.firstOrNull() ?: continue
            if (majority.getOrNull(1)?.value == target.value) continue
            moves[key] = target.key
        }
        return moves
    }
}
