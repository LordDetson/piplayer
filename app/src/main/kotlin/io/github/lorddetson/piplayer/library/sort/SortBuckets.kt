package io.github.lorddetson.piplayer.library.sort

/** The kinds of buckets of an alphabetic index, as ICU names them. */
enum class BucketKind { UNDERFLOW, NORMAL, INFLOW, OVERFLOW }

/**
 * The letters of the scroll thumb and of the controller tree (docs/library.md §11.2): runs of keys in the collator's
 * order, run i starting at the primary key [bounds] i and showing [labels] i. A letter may have more than one run, as the
 * Latin letters of Chinese pinyin have one among the Han characters and one among the Latin ones. A row's letter is
 * found from its stored key, so it agrees with the order.
 */
class SortBuckets(val labels: List<String>, private val bounds: List<SortKey>) {

    /** The run of [key]: the last one whose lower bound is not above the key's primary part; null is an empty name. */
    fun indexOf(key: SortKey?): Int {
        val primary = key?.primary ?: return 0
        // The last bound that is not above the key; the first bound is empty, so there is one.
        var low = 0
        var high = bounds.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (bounds[mid] <= primary) low = mid else high = mid - 1
        }
        return low
    }

    fun labelOf(key: SortKey?): String = labels[indexOf(key)]

    companion object {
        /** Digits, symbols and empty names. */
        const val UNDERFLOW = "#"

        /** Scripts without letters of their own. */
        const val OTHER = "…"

        const val PINYIN = "\uFDD0"

        /** One bucket for everything, until a collation is known. */
        val NONE = SortBuckets(listOf(UNDERFLOW), listOf(SortKey.EMPTY))

        /**
         * The buckets of an alphabetic index: its [labels] and their [kinds], [bucketOf] the index's own bucket of a text,
         * [key] the collator's key. A letter's runs start at the letter (and at U+FDD0 and the letter for pinyin); a bucket
         * of other scripts has a run for each of [scriptStarts] (the collator's strings that open each script) that the
         * index puts into it. [letterStarts] are the collator's own strings that start letters, U+FDD0 and a letter.
         */
        fun build(
            labels: List<String>,
            kinds: List<BucketKind>,
            scriptStarts: List<String>,
            bucketOf: (String) -> Int,
            key: (String) -> SortKey,
            letterStarts: Set<String> = emptySet(),
        ): SortBuckets {
            require(labels.size == kinds.size && labels.isNotEmpty())
            // Each entry says that a run of bucket i starts at a string the index puts into bucket i. With every lower
            // bound of the index among them, the runs between them belong to their buckets.
            val starts = HashMap<SortKey, Int>()
            starts[SortKey.EMPTY] = 0
            for (i in labels.indices) {
                if (kinds[i] != BucketKind.NORMAL) continue
                // A letter starts at itself; the Latin letters of pinyin also start at U+FDD0 and the letter.
                val bounds = listOf(labels[i], PINYIN + labels[i])
                    .filter { (it == labels[i] || it in letterStarts) && bucketOf(it) == i }
                    .ifEmpty { listOf(labels[i]) }
                for (bound in bounds) starts.putIfAbsent(key(bound).primary, i)
            }
            // A bucket of other scripts starts with each script that the index files into it.
            for (text in scriptStarts) {
                val i = bucketOf(text)
                if (i in labels.indices && (kinds[i] == BucketKind.INFLOW || kinds[i] == BucketKind.OVERFLOW)) {
                    starts.putIfAbsent(key(text).primary, i)
                }
            }
            val runs = starts.entries.sortedBy { it.key }
            return SortBuckets(runs.map { labels[it.value] }, runs.map { it.key })
        }
    }
}
