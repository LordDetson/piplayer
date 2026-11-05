package io.github.lorddetson.piplayer.library.songs

/** A list that keeps its order while the tag pass changes titles (docs/library.md §5.4). */
object FrozenOrder {

    /**
     * The rows of [fresh], those [kept] holds in its order; a new row goes after the kept row before it in [fresh], so
     * that what is on the screen does not move.
     */
    fun merge(kept: List<Song>, fresh: List<Song>): List<Song> {
        val freshIds = HashSet<Long>(fresh.size * 2).apply { fresh.forEach { add(it.id) } }
        val keptIds = HashSet<Long>(kept.size * 2).apply { kept.forEach { add(it.id) } }
        val values = HashMap<Long, Song>(fresh.size * 2).apply { fresh.forEach { put(it.id, it) } }

        val first = mutableListOf<Song>()
        val after = HashMap<Long, MutableList<Song>>()
        var lastKept: Long? = null
        for (song in fresh) {
            if (song.id in keptIds) {
                lastKept = song.id
            } else {
                val anchor = lastKept
                if (anchor == null) first += song else after.getOrPut(anchor) { mutableListOf() } += song
            }
        }

        val merged = ArrayList<Song>(fresh.size)
        merged += first
        for (song in kept) {
            if (song.id !in freshIds) continue
            merged += values.getValue(song.id)
            after[song.id]?.let { merged += it }
        }
        return merged
    }
}
