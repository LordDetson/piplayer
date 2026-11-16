package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.identification.caa.CoverImage
import io.github.lorddetson.piplayer.identification.caa.CoverSizeRule
import io.github.lorddetson.piplayer.identification.caa.CurrentCover
import io.github.lorddetson.piplayer.identification.release.ReleaseDetails
import io.github.lorddetson.piplayer.library.details.CoverSource
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.details.DetailValue
import io.github.lorddetson.piplayer.library.details.PictureProbe
import io.github.lorddetson.piplayer.library.details.PictureSize
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
 * release's, or the user's own when [edited]: TI §6, review screen B), null for none, and whether its checkbox is
 * [ticked].
 */
data class DiffLine(
    val field: ProtectedField,
    val old: FieldValue?,
    val new: FieldValue?,
    val ticked: Boolean,
    val edited: Boolean = false,
) {

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

/** A cover the card proposes (TI §6): the release's front image, or an image the cover picker chose. */
sealed interface ProposedCover {
    val bytes: ByteArray

    /** From the image header; null when the format does not say it without decoding (WebP). */
    val size: PictureSize?

    /** A release group's front image, taken from another edition (TI §5.2). */
    val anotherEdition: Boolean

    /** The release's front image of TI §5.2, which Apply stores with the identification. */
    class Front(val image: CoverImage) : ProposedCover {
        override val bytes: ByteArray get() = image.bytes
        override val size: PictureSize? get() = image.size
        override val anotherEdition: Boolean get() = image.anotherEdition

        override fun equals(other: Any?): Boolean = other is Front && other.image === image

        override fun hashCode(): Int = System.identityHashCode(image)

        override fun toString(): String = "Front($image)"
    }

    /** An image the cover picker chose from [origin] (TI §6, review screen B), which Apply stores as the user's edit. */
    class Chosen(override val bytes: ByteArray, override val size: PictureSize?, val origin: Origin) : ProposedCover {
        override val anotherEdition: Boolean get() = false

        override fun toString(): String = "Chosen($origin, $size, ${bytes.size} bytes)"
    }

    /** Where a chosen image comes from. */
    enum class Origin { RELEASE_INDEX, EMBEDDED }
}

/**
 * The cover line of the diff: the cover shown now ([old]), the proposed image ([new]) and whether its checkbox is
 * [ticked].
 */
data class CoverLine(val old: ShownCover, val new: ProposedCover, val ticked: Boolean) {

    /** An own cover wins, so it stays on top whatever Apply saves. */
    val ownStaysOnTop: Boolean get() = old is ShownCover.Own

    /** The image is the cover picker's, which Apply stores as the user's edit. */
    val chosen: Boolean get() = new is ProposedCover.Chosen
}

/** Builds the diff of the review (TI §6, review screen A). */
object ReviewDiff {

    /** How the lists of a release's values are shown, as tags write a list (TI §7.5). */
    internal const val LIST_SEPARATOR = "; "

    /** The old values: what track details show for each field of the protected-fields list now. */
    fun current(details: TrackDetails?): Map<ProtectedField, FieldValue> {
        if (details == null) return emptyMap()
        val values = LinkedHashMap<ProtectedField, FieldValue>()
        fun text(field: ProtectedField, detail: DetailField) {
            when (val value = details.value(detail)) {
                is DetailValue.Text -> text(value.text)?.let { values[field] = it }
                is DetailValue.Texts -> text(value.texts.joinToString(LIST_SEPARATOR))?.let { values[field] = it }
                else -> Unit
            }
        }
        fun ofTotal(field: ProtectedField, detail: DetailField) {
            (details.value(detail) as? DetailValue.OfTotal)?.let { values[field] = FieldValue.OfTotal(it.number, it.total) }
        }
        text(ProtectedField.TITLE, DetailField.TITLE)
        text(ProtectedField.ARTIST, DetailField.ARTIST)
        text(ProtectedField.ALBUM, DetailField.ALBUM)
        text(ProtectedField.ALBUM_ARTIST, DetailField.ALBUM_ARTIST)
        text(ProtectedField.DATE, DetailField.DATE)
        text(ProtectedField.ORIGINAL_DATE, DetailField.ORIGINAL_DATE)
        ofTotal(ProtectedField.TRACK_NUMBER, DetailField.TRACK)
        ofTotal(ProtectedField.DISC_NUMBER, DetailField.DISC)
        text(ProtectedField.DISC_SUBTITLE, DetailField.DISC_SUBTITLE)
        (details.value(DetailField.COMPILATION) as? DetailValue.Flag)?.let { values[ProtectedField.COMPILATION] = FieldValue.Flag(it.on) }
        text(ProtectedField.LABEL, DetailField.LABEL)
        text(ProtectedField.CATALOG_NUMBER, DetailField.CATALOG_NUMBER)
        text(ProtectedField.BARCODE, DetailField.BARCODE)
        text(ProtectedField.ISRC, DetailField.ISRC)
        val ids = ID_FIELDS.flatMap { field -> details.values(field).mapNotNull { (it as? DetailValue.MusicBrainz)?.id?.let(::plain) } }
        if (ids.isNotEmpty()) values[ProtectedField.MUSICBRAINZ_IDS] = FieldValue.Ids(ids.toSet())
        return values
    }

    /**
     * The cover track details show now. A source this build does not name here (a saved or an online cover) counts by
     * its size when details know it, otherwise as a cover of unknown size, which never keeps a proposed cover unticked.
     */
    fun shownCover(details: TrackDetails?): ShownCover {
        val source = (details?.value(DetailField.COVER_SOURCE) as? DetailValue.Cover)?.source ?: return ShownCover.None
        val size = details.value(DetailField.COVER_SIZE) as? DetailValue.Pixels
        return when (source) {
            CoverSource.SONG_OWN -> ShownCover.Own(song = true)
            CoverSource.ALBUM_OWN -> ShownCover.Own(song = false)
            CoverSource.NONE -> ShownCover.None
            CoverSource.THUMBNAIL -> ShownCover.Thumbnail
            else -> size?.let { ShownCover.Known(it.width, it.height, it.bytes) } ?: ShownCover.Thumbnail
        }
    }

    /** The new values: the chosen release's (TI §5.1). */
    fun proposed(details: ReleaseDetails): Map<ProtectedField, FieldValue> {
        val values = LinkedHashMap<ProtectedField, FieldValue>()
        fun text(field: ProtectedField, value: String?) {
            text(value)?.let { values[field] = it }
        }
        fun list(field: ProtectedField, items: List<String>) = text(field, items.joinToString(LIST_SEPARATOR))
        text(ProtectedField.TITLE, details.title)
        text(ProtectedField.ARTIST, details.trackArtist)
        text(ProtectedField.ALBUM, details.releaseTitle)
        text(ProtectedField.ALBUM_ARTIST, details.releaseArtist)
        text(ProtectedField.DATE, details.date)
        text(ProtectedField.ORIGINAL_DATE, details.originalDate)
        values[ProtectedField.TRACK_NUMBER] = FieldValue.OfTotal(details.at.position, details.trackTotal)
        values[ProtectedField.DISC_NUMBER] = FieldValue.OfTotal(details.at.disc, details.discTotal.takeIf { it > 0 })
        text(ProtectedField.DISC_SUBTITLE, details.discSubtitle)
        values[ProtectedField.COMPILATION] = FieldValue.Flag(details.compilation)
        list(ProtectedField.LABEL, details.labels)
        list(ProtectedField.CATALOG_NUMBER, details.catalogNumbers)
        text(ProtectedField.BARCODE, details.barcode)
        list(ProtectedField.ISRC, details.isrcs)
        val ids = listOfNotNull(details.recordingId, details.releaseId, details.releaseGroupId, details.trackId) +
            details.trackCredits.mapNotNull { it.artistId } + details.releaseCredits.mapNotNull { it.artistId }
        val plainIds = ids.mapNotNull(::plain).toSet()
        if (plainIds.isNotEmpty()) values[ProtectedField.MUSICBRAINZ_IDS] = FieldValue.Ids(plainIds)
        return values
    }

    /**
     * One line per field of the protected-fields list, in its order, that is not [protected] and has an old or a new
     * value; the cover has a line of its own ([coverLine]). An equal pair and a changed line with a new value start
     * ticked; a line whose new value is empty while the old one is not starts unticked, since applying it clears the field.
     * The compilation flag off is the release's value, not an empty one (TI §6), so it starts ticked.
     */
    fun lines(
        current: Map<ProtectedField, FieldValue>,
        proposed: Map<ProtectedField, FieldValue>,
        protected: Set<ProtectedField>,
    ): List<DiffLine> = ProtectedField.entries.filter { it != ProtectedField.COVER && it !in protected }.mapNotNull { field ->
        val old = current[field]?.takeIf(::present)
        val new = proposed[field]
        val newPresent = new != null && present(new)
        if (old == null && !newPresent) return@mapNotNull null
        // A release that is no compilation says so: a value, even though it shows as off.
        val newValue = if (newPresent || new is FieldValue.Flag) new else null
        DiffLine(field, old, newValue, ticked = newValue != null || sameValue(old, newValue))
    }

    /**
     * The cover line for [image] over [shown]; null without a proposed image, for an image without its release or release
     * group (Apply stores a cover with its source) or with the cover protected.
     */
    suspend fun coverLine(shown: ShownCover, image: CoverImage?, protected: Set<ProtectedField> = emptySet()): CoverLine? {
        if (image?.source == null || ProtectedField.COVER in protected) return null
        return CoverLine(shown, ProposedCover.Front(image), startsTicked(shown, image.size))
    }

    /**
     * The cover line for an image the cover picker [chosen] (TI §6, review screen B): its checkbox starts by the size rule
     * against the cover [shown] now, as the proposed cover's does. Null with the cover protected.
     */
    suspend fun chosenLine(shown: ShownCover, chosen: ProposedCover.Chosen, protected: Set<ProtectedField> = emptySet()): CoverLine? {
        if (ProtectedField.COVER in protected) return null
        return CoverLine(shown, chosen, startsTicked(shown, chosen.size))
    }

    /** Whether a cover of [size] starts ticked over the cover [shown] now (TI §5.2, §6). */
    private suspend fun startsTicked(shown: ShownCover, size: PictureSize?): Boolean = when (shown) {
        // A saved cover ranks below own covers: it would change nothing shown while the own cover stays.
        is ShownCover.Own -> false
        ShownCover.None -> CoverSizeRule.startsTicked(size, CurrentCover.None, NO_PROBE)
        ShownCover.Thumbnail -> CoverSizeRule.startsTicked(size, CurrentCover.SystemThumbnail, NO_PROBE)
        is ShownCover.Known -> CoverSizeRule.startsTicked(size, CurrentCover.Known(PictureSize(shown.width, shown.height)), NO_PROBE)
    }

    /**
     * [lines] with the user's [edits] of Edit fields (TI §6, review screen B) over the release's [proposed] values: an
     * edited field's line holds the user's value as its new one, marked as edited and ticked; an edit equal to the
     * release's value is none and leaves the release's line. A field without a line gets one, in the list's order.
     */
    fun withEdits(
        lines: List<DiffLine>,
        current: Map<ProtectedField, FieldValue>,
        proposed: Map<ProtectedField, FieldValue>,
        edits: Map<ProtectedField, FieldValue?>,
    ): List<DiffLine> {
        // An edit equal to the release's value (an emptied field where the release has none included) is no edit.
        val real = edits.filter { (field, value) -> field != ProtectedField.COVER && !sameValue(value, proposed[field]) }
        if (real.isEmpty()) return lines
        val byField = lines.associateBy { it.field }
        return ProtectedField.entries.mapNotNull { field ->
            if (field in real) {
                DiffLine(field, current[field]?.takeIf(::present), real[field]?.takeIf(::present), ticked = true, edited = true)
            } else {
                byField[field]
            }
        }
    }

    /** The fields Apply stores: the ticked lines' and the cover when its line is ticked. */
    fun fields(lines: List<DiffLine>, cover: CoverLine?): Set<ProtectedField> =
        lines.filter { it.ticked }.mapTo(LinkedHashSet()) { it.field }.apply { if (cover?.ticked == true) add(ProtectedField.COVER) }

    /**
     * What Apply stores of a card (TI §6, review screen B): the identification's fields (the ticked lines with the
     * release's values, and the proposed cover when ticked), and the user's own values (the ticked edited lines) with the
     * ticked chosen cover for the edit decision after it.
     */
    fun split(lines: List<DiffLine>, cover: CoverLine?): ApplyParts {
        val ticked = lines.filter { it.ticked }
        val identification = ticked.filter { !it.edited }.mapTo(LinkedHashSet()) { it.field }
        val coverTicked = cover?.ticked == true
        if (coverTicked && cover?.new is ProposedCover.Front) identification += ProtectedField.COVER
        val edits = ticked.filter { it.edited }.associate { it.field to it.new }
        val chosen = (cover?.new as? ProposedCover.Chosen)?.takeIf { coverTicked }
        return ApplyParts(identification, edits, chosen)
    }

    /** The ID rows of track details, which the diff takes as one set. */
    private val ID_FIELDS = listOf(
        DetailField.RECORDING_ID,
        DetailField.RELEASE_ID,
        DetailField.RELEASE_GROUP_ID,
        DetailField.TRACK_ID,
        DetailField.ARTIST_ID,
        DetailField.ALBUM_ARTIST_ID,
    )

    /** Details already show each cover's size: the rule never needs to read a header. */
    private val NO_PROBE = object : PictureProbe {
        override suspend fun embedded(uri: String): PictureSize? = null

        override suspend fun hasThumbnail(uri: String): Boolean = false
    }

    private fun text(value: String?): FieldValue.Text? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(FieldValue::Text)

    private fun plain(id: String): String? = id.trim().lowercase().takeIf { it.isNotEmpty() }
}

/**
 * What Apply stores (TI §6, review screen B): the [identification]'s fields, and the user's own [edits] with the
 * [chosen] cover, which go into one edit decision after it.
 */
data class ApplyParts(
    val identification: Set<ProtectedField>,
    val edits: Map<ProtectedField, FieldValue?>,
    val chosen: ProposedCover.Chosen?,
) {
    /** Whether anything goes into the edit decision. */
    val hasEdit: Boolean get() = edits.isNotEmpty() || chosen != null
}

/** Whether [value] is a value at all: the compilation flag off and an empty set of IDs are none. */
private fun present(value: FieldValue): Boolean = when (value) {
    is FieldValue.Text -> value.text.isNotBlank()
    is FieldValue.OfTotal -> true
    is FieldValue.Flag -> value.on
    is FieldValue.Ids -> value.ids.isNotEmpty()
}

/** Whether two values are the same for the diff; the compilation flag off is the same as none. */
internal fun sameValue(a: FieldValue?, b: FieldValue?): Boolean {
    val left = a?.takeIf(::present)
    val right = b?.takeIf(::present)
    return left == right
}
