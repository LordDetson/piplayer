package io.github.lorddetson.piplayer.library.sort

/**
 * A collator's key of a text as bytes (docs/library.md §11.1). Their unsigned order is the collator's order, which is also
 * how SQLite compares the BLOBs they are stored as.
 */
class SortKey(val bytes: ByteArray) : Comparable<SortKey> {

    override fun compareTo(other: SortKey): Int {
        val a = bytes
        val b = other.bytes
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /**
     * The primary part: the bytes before the first level separator (01), which letters are found by (§11.2). ICU keeps
     * 00 and 01 out of the weights, so the terminator 00 ends a key without levels.
     */
    val primary: SortKey get() {
        val end = bytes.indexOfFirst { it == LEVEL_SEPARATOR || it == TERMINATOR }
        return if (end < 0) this else SortKey(bytes.copyOf(end))
    }

    override fun equals(other: Any?): Boolean = other is SortKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.joinToString("", "SortKey(", ")") { "%02x".format(it) }

    companion object {
        val EMPTY = SortKey(ByteArray(0))

        private const val LEVEL_SEPARATOR: Byte = 1
        private const val TERMINATOR: Byte = 0

        /** A stored key, or null for none. */
        fun of(bytes: ByteArray?): SortKey? = bytes?.let(::SortKey)
    }
}
