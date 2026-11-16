package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.library.details.TrackDetails
import io.github.lorddetson.piplayer.settings.ProtectedField

/** A field's value as the review compares and shows it (TI §6, review screen A). */
sealed interface FieldValue {

    data class Text(val text: String) : FieldValue

    /** A track or disc number with its total when known. */
    data class OfTotal(val number: Int, val total: Int?) : FieldValue

    /** The compilation flag; off counts as no value. */
    data class Flag(val on: Boolean) : FieldValue

    /** MusicBrainz IDs, compared as one set and shown as a count. */
    data class Ids(val ids: Set<String>) : FieldValue
}

/**
 * One line of the diff: [field] with its [old] value (what track details show now) and its [new] one (the chosen
 * release's), null for none, and whether its checkbox is [ticked].
 */
data class DiffLine(val field: ProtectedField, val old: FieldValue?, val new: FieldValue?, val ticked: Boolean) {

    /** The old and the new value are the same: the line says "No change". */
    val unchanged: Boolean get() = sameValue(old, new)
}

/** The cover the user sees now, as track details show its source and size (docs/library.md §18.3). */
sealed interface ShownCover {

    data object None : ShownCover

    /** An own cover of the song ([song]) or of its album wins (docs/library.md §13.2). */
    data class Own(val song: Boolean) : ShownCover

    /** Android's thumbnail of the file, such as a folder image; its size is not kept. */
    data object Thumbnail : ShownCover

    /** A cover of known size: an embedded picture, or any other source whose size details show. */
    data class Known(val width: Int, val height: Int, val bytes: Long?) : ShownCover
}

/**
 * The cover line of the diff: the cover shown now ([old]), the proposed image ([new]) and whether its checkbox is
 * [ticked].
 */
data class CoverLine(val old: ShownCover, val new: CoverImage, val ticked: Boolean) {

    /** An own cover wins, so it stays on top whatever Apply saves. */
    val ownStaysOnTop: Boolean get() = old is ShownCover.Own
}

/** Builds the diff of the review (TI §6, review screen A). */
object ReviewDiff {

    /** The old values: what track details show for each field of the protected-fields list now. */
    fun current(details: TrackDetails?): Map<ProtectedField, FieldValue> = emptyMap()

    /** The cover track details show now. */
    fun shownCover(details: TrackDetails?): ShownCover = ShownCover.None

    /** The new values: the chosen release's (TI §5.1). */
    fun proposed(details: ReleaseDetails): Map<ProtectedField, FieldValue> = emptyMap()

    /**
     * One line per field of the protected-fields list, in its order, that is not [protected] and has an old or a new
     * value; the cover has a line of its own ([coverLine]).
     */
    fun lines(
        current: Map<ProtectedField, FieldValue>,
        proposed: Map<ProtectedField, FieldValue>,
        protected: Set<ProtectedField>,
    ): List<DiffLine> = emptyList()

    /** The cover line for [image] over [shown]; null without a proposed image or with the cover protected. */
    suspend fun coverLine(shown: ShownCover, image: CoverImage?, protected: Set<ProtectedField> = emptySet()): CoverLine? = null

    /** The fields Apply stores: the ticked lines' and the cover when its line is ticked. */
    fun fields(lines: List<DiffLine>, cover: CoverLine?): Set<ProtectedField> = emptySet()
}

/** Whether two values are the same for the diff; the compilation flag off is the same as none. */
internal fun sameValue(a: FieldValue?, b: FieldValue?): Boolean = a == b
