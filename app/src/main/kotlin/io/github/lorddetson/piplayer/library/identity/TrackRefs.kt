package io.github.lorddetson.piplayer.library.identity

/**
 * A track as the saved queue names it, the form of a playlist entry (TI §10.2, docs/playback.md §4.8): the file ID it
 * had, its identity when it had one, its content key, its place and its length.
 */
data class TrackRef(
    val fileId: Long,
    val identityId: Long?,
    val contentKey: String?,
    val place: Place?,
    val durationMs: Long?,
)

/** A file of the library as a lookup finds it. */
data class FoundFile(val fileId: Long, val place: Place, val contentKey: String?, val durationMs: Long?)

/** What resolving a [TrackRef] needs to know of the two databases. */
interface TrackLookup {
    fun file(fileId: Long): FoundFile?

    /** The current key of an identity, or null when there is no such identity. */
    fun identityKey(identityId: Long): String?

    fun filesWithKey(contentKey: String): List<FoundFile>

    fun filesAt(place: Place): List<FoundFile>
}

/** Finds the file a saved track reference names now (docs/playback.md §4.8). */
object TrackRefs {

    /**
     * The file ID of [ref] in the library now, or null. [sameLibrary] says whether the library ID is the one the reference
     * was saved under; only then does its file ID mean anything.
     */
    fun resolve(ref: TrackRef, sameLibrary: Boolean, lookup: TrackLookup): Long? {
        if (sameLibrary) {
            val file = lookup.file(ref.fileId)
            if (file != null && (ref.place == null || file.place == ref.place)) return file.fileId
        }
        val keys = listOfNotNull(ref.identityId?.let(lookup::identityKey), ref.contentKey).distinct()
        for (key in keys) {
            val copies = lookup.filesWithKey(key)
            if (copies.isNotEmpty()) return (copies.firstOrNull { it.place == ref.place } ?: copies.minBy { it.fileId }).fileId
        }
        ref.place?.let { place ->
            lookup.filesAt(place).firstOrNull { IdentityMatcher.withinWindow(it.durationMs, ref.durationMs) }
                ?.let { return it.fileId }
        }
        // The same file at another place, whose key is not known (yet).
        return if (sameLibrary) lookup.file(ref.fileId)?.fileId else null
    }
}
