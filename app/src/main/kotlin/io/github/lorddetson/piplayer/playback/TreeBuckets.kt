package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.sort.Sections

/**
 * A bucket of a long list (docs/playback.md §10.2): the rows [from] until [to] of the list, the [part]th of the [parts]
 * buckets of [section], or with an empty section a run of rows.
 */
data class TreeBucket(val section: String, val part: Int, val parts: Int, val from: Int, val to: Int) {
    val title: String
        get() = when {
            section.isEmpty() -> "${from + 1}-$to"
            parts > 1 -> "$section ($part)"
            else -> section
        }
}

/** How long lists of the library tree split, and how a page of children is cut (docs/playback.md §10.2). */
object TreeBuckets {

    /** No node has more children. */
    const val MAX_CHILDREN = 500

    /**
     * No node's children take more bytes in the form a legacy browser gets them: Media3 cuts that list without a word
     * where it reaches 256 KB, so the tree stays below it with room to spare.
     */
    const val MAX_BYTES = 240 * 1024

    /**
     * The buckets of a list of [size] entries whose sections [sectionAt] gives in list order, or none when the list fits in
     * one node: at most [max] entries that weigh at most [budget] bytes together, [weightAt] giving each entry's weight.
     */
    fun of(
        size: Int,
        sectionAt: (Int) -> String?,
        weightAt: (Int) -> Int = { 0 },
        max: Int = MAX_CHILDREN,
        budget: Int = MAX_BYTES,
    ): List<TreeBucket> {
        val weights = IntArray(size) { weightAt(it) }
        if (size <= max && weights.sumOf { it.toLong() } <= budget) return emptyList()
        val sections = arrayOfNulls<String>(size)
        var any = false
        for (i in 0 until size) {
            sections[i] = sectionAt(i)?.takeIf { it.isNotEmpty() }
            if (sections[i] != null) any = true
        }
        if (!any) return rows(weights, max, budget)
        // Runs of one section, each cut where it reaches [max] entries or [budget] bytes.
        val pieces = ArrayList<Triple<String, Int, Int>>()
        var start = 0
        while (start < size) {
            val section = sections[start] ?: Sections.MISSING
            val end = cut(weights, start, max, budget) { (sections[it] ?: Sections.MISSING) == section }
            pieces += Triple(section, start, end)
            if (pieces.size > max) return rows(weights, max, budget)
            start = end
        }
        val totals = pieces.groupingBy { it.first }.eachCount()
        val counted = HashMap<String, Int>()
        return pieces.map { (section, from, to) ->
            val part = counted.merge(section, 1, Int::plus)!!
            TreeBucket(section, part, totals.getValue(section), from, to)
        }
    }

    /** Runs of rows, each cut where it reaches [max] entries or [budget] bytes. */
    private fun rows(weights: IntArray, max: Int, budget: Int): List<TreeBucket> {
        val runs = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < weights.size) {
            val end = cut(weights, start, max, budget) { true }
            runs += start to end
            start = end
        }
        return runs.mapIndexed { i, (from, to) -> TreeBucket("", i + 1, runs.size, from, to) }
    }

    /** Where a piece that starts at [start] ends: at least one entry, then while [same] holds and it fits. */
    private inline fun cut(weights: IntArray, start: Int, max: Int, budget: Int, same: (Int) -> Boolean): Int {
        var end = start + 1
        var bytes = weights[start].toLong()
        while (end < weights.size && end - start < max && bytes + weights[end] <= budget && same(end)) {
            bytes += weights[end]
            end++
        }
        return end
    }

    /** The rows of page [page] of [pageSize] rows out of [size]; empty past the end. */
    fun page(size: Int, page: Int, pageSize: Int): IntRange {
        if (page < 0 || pageSize < 1) return IntRange.EMPTY
        val from = page.toLong() * pageSize
        if (from >= size) return IntRange.EMPTY
        val to = minOf(size.toLong(), from + pageSize)
        return from.toInt() until to.toInt()
    }

    /**
     * An upper bound of the bytes a browser item takes in a legacy browser's list (§10.2): its media ID of
     * [mediaIdLength] ASCII characters (UTF-16 in the parcel) and the texts it shows, [title], [subtitle] and
     * [description] (UTF-8 in the parcel since Android 11; an older Android, if the app ever runs on one, writes two bytes
     * a character), and what every item carries.
     */
    fun weight(mediaIdLength: Int, title: CharSequence?, subtitle: CharSequence?, description: CharSequence?): Int =
        ITEM_BYTES + 8 + 2 * mediaIdLength + text(title) + text(subtitle) + text(description)

    private fun text(text: CharSequence?): Int {
        if (text.isNullOrEmpty()) return 0
        var bytes = 12
        for (char in text) {
            bytes += when {
                char.code < 0x80 -> 1
                // A surrogate pair is four bytes.
                char.code < 0x800 || char.isSurrogate() -> 2
                else -> 3
            }
        }
        return bytes
    }

    /**
     * What every item takes besides its texts, with room to spare: the class name a parcel writes for each item, the
     * flags, the empty icon and URIs, and the media type in the extras. Measured on the API 30 emulator against the
     * platform's own media item (TreeItemWeightTest), at least 30 bytes above it.
     */
    private const val ITEM_BYTES = 320
}