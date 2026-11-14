package io.github.lorddetson.piplayer.library.playlists

import io.github.lorddetson.piplayer.library.identity.Identity
import io.github.lorddetson.piplayer.library.identity.IdentityMatcher
import io.github.lorddetson.piplayer.library.identity.KeyedFile
import io.github.lorddetson.piplayer.library.identity.Place
import java.util.Locale

/**
 * What a playlist entry knows of its song besides its identity (docs/library.md §16.2): a place, which may lack its
 * volume or folder (a line of an imported playlist), a length, a content key (hex) and a recording MBID.
 */
data class EntryHint(
    val volume: String? = null,
    val relativePath: String? = null,
    val displayName: String? = null,
    val durationMs: Long? = null,
    val contentKey: String? = null,
    val recordingMbid: String? = null,
) {
    /** The whole place, when the hint has one. */
    val place: Place? get() = if (volume != null && relativePath != null && displayName != null) Place(volume, relativePath, displayName) else null
}

/** A file of the library as hints are matched against it. */
data class HintFile(
    val fileId: Long,
    val place: Place,
    val durationMs: Long?,
    val contentKey: String? = null,
    val recordingMbid: String? = null,
)

/**
 * Finds the file a hint names among [files] (docs/library.md §16.2, §16.6), in this order: the content key, the place
 * with the length, the place ignoring case with the length, the display name ignoring case with the length (a name that
 * several files carry goes to the folder that ends most like the hint's, then to the lowest file ID), the recording
 * MBID with the length when one file alone carries it. A length that either side lacks fits, except for the MBID.
 */
class FileMatcher(files: List<HintFile>) {

    private val byKey = files.filter { it.contentKey != null }.groupBy { it.contentKey!! }
    private val byPlace = files.groupBy { it.place }
    private val byPlaceIgnoringCase = files.groupBy { lower(it.place) }
    private val byName = files.groupBy { it.place.displayName.lowercase(Locale.ROOT) }
    private val byMbid = files.filter { it.recordingMbid != null }.groupBy { it.recordingMbid!! }

    fun find(hint: EntryHint): HintFile? {
        val place = hint.place
        hint.contentKey?.let { key ->
            byKey[key]?.let { copies -> return copies.firstOrNull { it.place == place } ?: copies.minBy { it.fileId } }
        }
        if (place != null) {
            byPlace[place].orEmpty().filter { lengthFits(hint.durationMs, it.durationMs) }.minByOrNull { it.fileId }?.let { return it }
            byPlaceIgnoringCase[lower(place)].orEmpty().filter { lengthFits(hint.durationMs, it.durationMs) }
                .minByOrNull { it.fileId }?.let { return it }
        }
        hint.displayName?.let { name ->
            val named = byName[name.lowercase(Locale.ROOT)].orEmpty().filter { lengthFits(hint.durationMs, it.durationMs) }
            if (named.isNotEmpty()) return closest(hint.relativePath, named)
        }
        hint.recordingMbid?.let { mbid ->
            byMbid[mbid].orEmpty().singleOrNull()?.takeIf { IdentityMatcher.withinWindow(hint.durationMs, it.durationMs) }?.let { return it }
        }
        return null
    }

    private fun lower(place: Place) = Place(
        place.volume.lowercase(Locale.ROOT),
        place.relativePath.lowercase(Locale.ROOT),
        place.displayName.lowercase(Locale.ROOT),
    )

    companion object {
        /** Whether two lengths lie within 1 s, or one of them is unknown. */
        fun lengthFits(hinted: Long?, actual: Long?): Boolean =
            hinted == null || actual == null || IdentityMatcher.withinWindow(hinted, actual)

        /** The file whose folder ends with the most folders of [relativePath], then the lowest file ID. */
        fun closest(relativePath: String?, files: List<HintFile>): HintFile {
            val wanted = folders(relativePath)
            return files.minWith(compareByDescending<HintFile> { commonTail(wanted, folders(it.place.relativePath)) }.thenBy { it.fileId })
        }

        private fun folders(relativePath: String?): List<String> =
            relativePath.orEmpty().lowercase(Locale.ROOT).split('/').filter { it.isNotEmpty() }

        private fun commonTail(a: List<String>, b: List<String>): Int {
            var count = 0
            while (count < a.size && count < b.size && a[a.size - 1 - count] == b[b.size - 1 - count]) count++
            return count
        }
    }
}

/** Step 5 of re-attachment and the linking of entries without an identity (TI §3.1, docs/library.md §16.2). */
object PlaylistHints {

    /**
     * Step 5: each missing identity of [missing] that entries refer to with [hints] takes the one file of [unattached]
     * that one of its hints names by place, by display name ignoring case or by recording MBID, with a length within 1 s
     * of the hint's (else the identity's), when no other missing identity could take that file.
     */
    fun reattach(missing: List<Identity>, hints: Map<Long, List<EntryHint>>, unattached: List<KeyedFile>): Map<Long, KeyedFile> {
        if (hints.isEmpty() || unattached.isEmpty()) return emptyMap()
        val candidates = missing.mapNotNull { identity ->
            val own = hints[identity.id] ?: return@mapNotNull null
            identity.id to unattached.filter { file -> own.any { hint -> names(hint, identity, file) } }
        }
        val claims = candidates.flatMap { (_, files) -> files.map { it.fileId } }.groupingBy { it }.eachCount()
        return candidates.mapNotNull { (id, files) ->
            files.singleOrNull()?.takeIf { claims[it.fileId] == 1 }?.let { id to it }
        }.toMap()
    }

    private fun names(hint: EntryHint, identity: Identity, file: KeyedFile): Boolean {
        if (!IdentityMatcher.withinWindow(file.durationMs, hint.durationMs ?: identity.durationMs)) return false
        return hint.place == file.place ||
            hint.displayName?.equals(file.place.displayName, ignoreCase = true) == true ||
            (hint.recordingMbid != null && hint.recordingMbid == file.recordingMbid)
    }

    /** The file of [files] each entry of [entries] (by its row ID) names now, for the entries that name one. */
    fun link(entries: Map<Long, EntryHint>, files: List<HintFile>): Map<Long, HintFile> {
        if (entries.isEmpty() || files.isEmpty()) return emptyMap()
        val matcher = FileMatcher(files)
        return entries.mapNotNull { (id, hint) -> matcher.find(hint)?.let { id to it } }.toMap()
    }
}

/** The file an entry plays (docs/library.md §16.2). */
object PlaylistResolution {

    /** An entry as it is resolved: its identity with the identity's key and hinted place, and its own hints. */
    data class Target(val identityId: Long?, val identityKey: String?, val identityHint: Place?, val hint: EntryHint)

    /**
     * The file [target] plays: with an identity, a copy of its key, the one at the identity's place first, then the one
     * at the entry's place, then the lowest file ID, and nothing while no file holds the key; without one, a copy of
     * the hinted key, else the file at the hinted place whose length fits.
     */
    fun fileOf(target: Target, withKey: (String) -> List<HintFile>, atPlace: (Place) -> List<HintFile>): HintFile? {
        val place = target.hint.place
        if (target.identityId != null) {
            val key = target.identityKey ?: return null
            return pick(withKey(key), target.identityHint, place)
        }
        target.hint.contentKey?.let { key -> pick(withKey(key), place, null)?.let { return it } }
        place ?: return null
        return atPlace(place).filter { FileMatcher.lengthFits(target.hint.durationMs, it.durationMs) }.minByOrNull { it.fileId }
    }

    private fun pick(copies: List<HintFile>, first: Place?, second: Place?): HintFile? {
        if (copies.isEmpty()) return null
        return copies.firstOrNull { it.place == first } ?: copies.firstOrNull { it.place == second } ?: copies.minBy { it.fileId }
    }
}
