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

    /** How the lists of a release's values are shown, as tags write a list (TI §7.5). */
    private const val LIST_SEPARATOR = "; "

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
        val ticked = when (shown) {
            // A saved cover ranks below own covers: it would change nothing shown while the own cover stays.
            is ShownCover.Own -> false
            ShownCover.None -> CoverSizeRule.startsTicked(image.size, CurrentCover.None, NO_PROBE)
            ShownCover.Thumbnail -> CoverSizeRule.startsTicked(image.size, CurrentCover.SystemThumbnail, NO_PROBE)
            is ShownCover.Known -> CoverSizeRule.startsTicked(image.size, CurrentCover.Known(PictureSize(shown.width, shown.height)), NO_PROBE)
        }
        return CoverLine(shown, image, ticked)
    }

    /** The fields Apply stores: the ticked lines' and the cover when its line is ticked. */
    fun fields(lines: List<DiffLine>, cover: CoverLine?): Set<ProtectedField> =
        lines.filter { it.ticked }.mapTo(LinkedHashSet()) { it.field }.apply { if (cover?.ticked == true) add(ProtectedField.COVER) }

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
