package io.github.lorddetson.piplayer.identification.contract

import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.settings.ProtectedField

/**
 * The file an identification is for. Its [contentKey] (TI §3.1, 64 lower-case hex digits as the library stores it) and
 * [startMs] form the track key that the decision attaches to (TI §10); the working rows of identification are kept by
 * the content key too (TI §10.1).
 */
sealed interface IdentificationTarget {
    val contentKey: String

    /** The track's start in the file, 0 until CUE sheets arrive. */
    val startMs: Long

    /** A file of the library, by its file ID (docs/library.md §8.4). */
    data class LibraryFile(val fileId: Long, override val contentKey: String, override val startMs: Long = 0) : IdentificationTarget {
        init {
            checkKey(contentKey, startMs)
        }
    }

    /**
     * A file opened from another app (TI §1 entry points): no file ID, its [uri] and, for the identity's hints, the
     * display name and size the app gave (M3 step 16).
     */
    data class OpenedFile(
        val uri: String,
        override val contentKey: String,
        val displayName: String?,
        val size: Long?,
        override val startMs: Long = 0,
    ) : IdentificationTarget {
        init {
            checkKey(contentKey, startMs)
        }
    }
}

private fun checkKey(contentKey: String, startMs: Long) {
    require(contentKey.length == 64 && contentKey.all { it in '0'..'9' || it in 'a'..'f' }) {
        "A content key is 64 lower-case hex digits"
    }
    require(startMs >= 0) { "A track starts at 0 or later: $startMs" }
}

/**
 * What Apply in the review (M3 step 18) asks the decision repository (step 12) to store (TI §6, §7.1): for [target], the
 * values of [details] in the ticked [fields] (the field names of TI §10), the front image of [cover] when the cover is
 * ticked, and the [provenance] of the values.
 */
data class ApplyRequest(
    val target: IdentificationTarget,
    val details: ReleaseDetails,
    val fields: Set<ProtectedField>,
    val cover: CoverSource?,
    val provenance: Provenance,
) {
    init {
        require(fields.isNotEmpty()) { "Apply needs at least one ticked field" }
        require((cover != null) == (ProtectedField.COVER in fields)) { "A cover goes with a ticked cover, and only then" }
        require(provenance.recordingId == details.recordingId) { "The provenance belongs to another recording" }
    }
}
