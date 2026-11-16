package io.github.lorddetson.piplayer.library.identity

import io.github.lorddetson.piplayer.nativelib.chromaprint.FingerprintComparison

/** Where step 4 gets fingerprints from: the cache of the library database, or a computation that fills it. */
interface FingerprintSource {
    /** The cached fingerprint of [contentKey], or null. */
    suspend fun cached(contentKey: String): CachedPrint?

    /** The fingerprint of [file], computed at background priority and cached by its key; null when it cannot be made. */
    suspend fun compute(file: KeyedFile): IntArray?
}

/** A cached fingerprint and the format of the file it was made of. */
class CachedPrint(val raw: IntArray, val format: String?)

/** A missing identity that takes over the file of a newer identity, which is deleted (docs/library.md §8.3). */
data class Takeover(val missingId: Long, val file: KeyedFile, val replacedId: Long) {

    companion object {
        /**
         * The [takeovers], found on what was read before step 4 or 5 ran, that still hold on [identities] and
         * [holdingData] as the step's write transaction reads them (docs/library.md §8.2): the missing identity is still
         * there and missing, and the replaced one is still there with the file's key and holds no user data. Step 4 can
         * fingerprint for minutes, and meanwhile the user may favorite, play or add to a playlist the new identity.
         */
        fun stillValid(takeovers: List<Takeover>, identities: List<Identity>, holdingData: Set<Long>): List<Takeover> {
            val byId = identities.associateBy { it.id }
            return takeovers.filter { takeover ->
                val missing = byId[takeover.missingId]
                val replaced = byId[takeover.replacedId]
                missing != null && missing.missingSince != null && replaced != null && replaced.id !in holdingData &&
                    replaced.contentKey == takeover.file.contentKey
            }
        }
    }
}

/**
 * Step 4 of re-attachment (TI §3.1, docs/library.md §8.3): a missing identity that holds user data takes over a file
 * whose fingerprint is similar to the one cached for its old key. Each pair of identity and file is compared once in the
 * life of this object.
 */
class FingerprintStep(
    private val fingerprints: FingerprintSource,
    private val similar: (IntArray, IntArray) -> Boolean = ::similarFingerprints,
) {

    /**
     * The takeovers for [identities] over [files], after the other steps have run: only identities in [holdingData]
     * look for a file, and only files whose identity is not in it may be taken. An identity that waits for the read of
     * the file at its hint ([IdentityMatcher.waitsForRead] with [unread]) looks for none.
     */
    suspend fun takeovers(
        files: List<KeyedFile>,
        identities: List<Identity>,
        holdingData: Set<Long>,
        unread: Set<Place> = emptySet(),
    ): List<Takeover> {
        val owners = identities.associateBy { it.contentKey }
        val attachedKeys = files.keys()
        val missing = identities.filter {
            it.id in holdingData && it.contentKey !in attachedKeys && it.missingSince != null && !IdentityMatcher.waitsForRead(it, unread)
        }
        val takenKeys = HashSet<String>()
        val result = mutableListOf<Takeover>()
        for (identity in missing.sortedByDescending { it.id }) {
            val since = identity.missingSince ?: continue
            val old = fingerprints.cached(identity.contentKey) ?: continue
            // One candidate per key: byte-identical copies are one sound.
            val candidates = files.filter { file ->
                val owner = owners[file.contentKey]
                owner != null && owner.id !in holdingData && owner.createdAt >= since && file.contentKey !in takenKeys &&
                    file.format == old.format && IdentityMatcher.withinWindow(file.durationMs, identity.durationMs)
            }.groupBy { it.contentKey }.map { (_, copies) -> copies.minBy { it.fileId } }
            val matches = candidates.filter { file -> matches(identity, old, file) }
            val file = matches.singleOrNull() ?: continue
            takenKeys += file.contentKey
            result += Takeover(identity.id, file, owners.getValue(file.contentKey).id)
        }
        return result
    }

    /** Whether [file] sounds like the old file of [identity]; each pair is compared once, a failed fingerprint again. */
    private suspend fun matches(identity: Identity, old: CachedPrint, file: KeyedFile): Boolean {
        val pair = identity.id to file.contentKey
        results[pair]?.let { return it }
        val print = fingerprints.cached(file.contentKey)?.raw ?: fingerprints.compute(file) ?: return false
        return similar(old.raw, print).also { results[pair] = it }
    }

    private val results = HashMap<Pair<Long, String>, Boolean>()

    private fun List<KeyedFile>.keys() = mapTo(HashSet()) { it.contentKey }

    companion object {
        /** At most this share of differing bits at the best offset, as for the M0 check against fpcalc. */
        const val MAX_BIT_ERROR_RATE = 0.10

        fun similarFingerprints(a: IntArray, b: IntArray): Boolean {
            val match = FingerprintComparison.bestMatch(a, b) ?: return false
            return match.bitErrorRate <= MAX_BIT_ERROR_RATE
        }
    }
}
