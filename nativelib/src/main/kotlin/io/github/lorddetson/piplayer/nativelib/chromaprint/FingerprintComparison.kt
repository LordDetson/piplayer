package io.github.lorddetson.piplayer.nativelib.chromaprint

/** How two raw fingerprints line up: the item offset of [b] against [a], and the share of differing bits there. */
data class FingerprintMatch(val offset: Int, val bitErrorRate: Double, val overlap: Int)

/**
 * Compares raw fingerprints the way AcoustID does in essence: shift one against the other within [maxOffset] items and
 * count differing bits over the overlap (32 bits per item).
 */
object FingerprintComparison {

    /** AcoustID's own search window. */
    const val MAX_OFFSET = 80

    /** The shortest overlap worth comparing: 8 items are about one second of audio. */
    const val MIN_OVERLAP = 8

    /**
     * The offset with the lowest bit error rate, among offsets whose overlap covers at least half of the shorter
     * fingerprint and at least [MIN_OVERLAP] items; null when the fingerprints are too short for that. An offset of
     * `n` means that item 0 of [b] lines up with item `n` of [a].
     */
    fun bestMatch(a: IntArray, b: IntArray, maxOffset: Int = MAX_OFFSET): FingerprintMatch? {
        val minOverlap = maxOf(MIN_OVERLAP, (minOf(a.size, b.size) + 1) / 2)
        var best: FingerprintMatch? = null
        for (offset in -maxOffset..maxOffset) {
            // Item j of b lines up with item j + offset of a.
            val first = maxOf(0, -offset)
            val last = minOf(b.size, a.size - offset)
            val overlap = last - first
            if (overlap < minOverlap) continue
            var errors = 0L
            for (j in first until last) errors += Integer.bitCount(a[j + offset] xor b[j])
            val rate = errors.toDouble() / (32L * overlap)
            if (best == null || rate < best.bitErrorRate) best = FingerprintMatch(offset, rate, overlap)
        }
        return best
    }
}
