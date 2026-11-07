package io.github.lorddetson.piplayer.library.sort

/** The section a row shows in the bubble of the scroll thumb (docs/library.md §11.2). */
object Sections {
    /** For a row without the value: no year, no length. */
    const val MISSING = "?"

    /** The letter of a stored key, by the current collation. */
    fun letter(key: SortKey?, buckets: SortBuckets = SortKeys.current().buckets): String = buckets.labelOf(key)

    fun year(year: Int?): String = year?.toString() ?: MISSING

    /** The whole minute of a length, as `3:00`. */
    fun minutes(durationMs: Long?): String = durationMs?.let { "${it / 60_000}:00" } ?: MISSING

    fun count(count: Int): String = count.toString()
}
