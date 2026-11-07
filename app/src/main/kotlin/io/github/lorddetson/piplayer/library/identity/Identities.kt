package io.github.lorddetson.piplayer.library.identity

/** Where a file lies: its volume, its folder as the media index gives it, and its name. */
data class Place(val volume: String, val relativePath: String, val displayName: String)

/**
 * A file of the library whose content key is known, as the attach pass sees it (docs/library.md §8.2). [settled] is
 * false while its length waits for a walk or a check of FLAC's end; such a file is left alone until it settles.
 */
data class KeyedFile(
    val fileId: Long,
    val contentKey: String,
    val place: Place,
    val size: Long,
    val durationMs: Long?,
    val settled: Boolean,
    val recordingMbid: String?,
    val format: String?,
    /** Whether the tag pass has read the file's tags, so that [recordingMbid] says what they hold. */
    val tagsRead: Boolean = true,
)

/**
 * A track identity of the user database (TI §10.2). [contentKey] is in hex here; the database keeps it as a blob.
 * [missingSince] is null while a file holds the key.
 */
data class Identity(
    val id: Long,
    val contentKey: String,
    val durationMs: Long?,
    val recordingMbid: String?,
    val hint: Place?,
    val hintSize: Long?,
    val createdAt: Long,
    val missingSince: Long?,
    val startMs: Long = 0,
) {
    /** This identity attached to [file]: its key, length (once settled), tag MBID and place. */
    fun following(file: KeyedFile) = copy(
        contentKey = file.contentKey,
        durationMs = if (file.settled) file.durationMs else durationMs,
        recordingMbid = if (file.tagsRead) file.recordingMbid else file.recordingMbid ?: recordingMbid,
        hint = file.place,
        hintSize = file.size,
        missingSince = null,
    )
}

/** What one attach pass writes into the user database. */
data class AttachPlan(
    /** New identities for files no identity held, one per key; the file names the hints. */
    val created: List<KeyedFile>,
    /** Identities with new values: a key taken over, a hint that followed its file, a `missing_since` set or cleared. */
    val updated: List<Identity>,
    /** Identities without user data that have been missing for [IdentityMatcher.KEEP_MISSING_MS]. */
    val deleted: List<Long>,
) {
    val isEmpty: Boolean get() = created.isEmpty() && updated.isEmpty() && deleted.isEmpty()
}

/** Steps 1, 2, 3 and 5 of re-attachment (TI §3.1, docs/library.md §8.2); step 4 is [FingerprintStep]. */
object IdentityMatcher {

    /** How far two lengths may lie apart for steps 2 to 4. */
    const val DURATION_WINDOW_MS = 1_000L

    /** How long an identity without user data is kept after its last file went. */
    const val KEEP_MISSING_MS = 30L * 24 * 60 * 60 * 1_000

    /**
     * The changes that attach [identities] to [files]: [holdingData] are the identities that user data refers to, which
     * are never deleted; [now] is the time of the pass.
     */
    fun plan(files: List<KeyedFile>, identities: List<Identity>, holdingData: Set<Long>, now: Long): AttachPlan {
        val filesByKey = files.groupBy { it.contentKey }
        val heldKeys = identities.mapTo(HashSet()) { it.contentKey }
        val result = LinkedHashMap<Long, Identity>()
        val missing = mutableListOf<Identity>()

        // Step 1: the key. The hints stay with a copy at the hinted place, and otherwise follow the lowest file.
        for (identity in identities) {
            val holders = filesByKey[identity.contentKey]
            if (holders == null) {
                missing += identity
                continue
            }
            val file = holders.firstOrNull { it.place == identity.hint } ?: holders.minBy { it.fileId }
            result[identity.id] = identity.following(file)
        }

        // Files no identity holds, once their length is settled; one per key.
        val unattached = files.filter { it.settled && it.contentKey !in heldKeys }
            .groupBy { it.contentKey }
            .map { (_, copies) -> copies.minBy { it.fileId } }
            .toMutableList()

        // Step 2: the hinted place with the length; the newest identity first.
        for (identity in missing.sortedByDescending { it.id }) {
            val file = unattached.firstOrNull { it.place == identity.hint && withinWindow(it.durationMs, identity.durationMs) }
                ?: continue
            unattached -= file
            result[identity.id] = identity.following(file)
        }

        // Step 3: the one unattached file and the one missing identity with a recording MBID.
        val stillMissing = missing.filter { it.id !in result }
        val missingByMbid = stillMissing.filter { it.recordingMbid != null }.groupBy { it.recordingMbid }
        // Files whose length is still pending count for the uniqueness, so that the first to settle does not win.
        val pendingMbids = files.filter { !it.settled && it.contentKey !in heldKeys }.mapNotNull { it.recordingMbid }.toSet()
        val unattachedByMbid = unattached.filter { it.recordingMbid != null }.groupBy { it.recordingMbid }
        for ((mbid, candidates) in missingByMbid) {
            val identity = candidates.singleOrNull() ?: continue
            if (mbid in pendingMbids) continue
            val file = unattachedByMbid[mbid]?.singleOrNull() ?: continue
            if (!withinWindow(file.durationMs, identity.durationMs)) continue
            unattached -= file
            result[identity.id] = identity.following(file)
        }

        // What stays missing is marked, and goes after a while unless user data refers to it.
        val deleted = mutableListOf<Long>()
        for (identity in missing) {
            if (identity.id in result) continue
            val since = identity.missingSince
            when {
                since == null -> result[identity.id] = identity.copy(missingSince = now)
                now - since >= KEEP_MISSING_MS && identity.id !in holdingData -> deleted += identity.id
            }
        }

        val before = identities.associateBy { it.id }
        val updated = result.values.filter { it != before[it.id] }
        // Step 5: a new identity for every file left.
        return AttachPlan(created = unattached.sortedBy { it.fileId }, updated = updated, deleted = deleted)
    }

    fun withinWindow(a: Long?, b: Long?): Boolean =
        a != null && b != null && kotlin.math.abs(a - b) <= DURATION_WINDOW_MS
}
