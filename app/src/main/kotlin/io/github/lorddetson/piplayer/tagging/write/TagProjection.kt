package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.PartKind
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.KeyFormat

internal enum class TagFileFormat { ID3V23, ID3V24, VORBIS, MP4, WAV, UNSUPPORTED }

internal fun TagReport.writeFormat(): TagFileFormat = when (content?.token) {
    KeyFormat.MP3.token -> if (id3Version == 2 || id3Version == 3) TagFileFormat.ID3V23 else TagFileFormat.ID3V24
    KeyFormat.FLAC.token, KeyFormat.OGG_VORBIS.token, KeyFormat.OPUS.token -> TagFileFormat.VORBIS
    KeyFormat.MP4.token -> TagFileFormat.MP4
    KeyFormat.WAV.token -> TagFileFormat.WAV
    else -> TagFileFormat.UNSUPPORTED
}

internal data class TagProjection(
    val properties: Map<String, List<String>>,
    val unsupported: Set<DecidedField>,
)

internal fun projectTags(
    format: TagFileFormat,
    current: Map<String, List<String>>,
    decided: Map<DecidedField, String?>,
): TagProjection {
    if (format == TagFileFormat.UNSUPPORTED) return TagProjection(emptyMap(), decided.keys.toSet())

    val properties = linkedMapOf<String, List<String>>()
    val unsupported = mutableSetOf<DecidedField>()
    for ((field, value) in decided) {
        if (field in positionFields) continue
        val key = field.propertyKey
        if (key == null || field == DecidedField.DISC_SUBTITLE && format == TagFileFormat.ID3V23) {
            unsupported += field
            continue
        }
        val values = when (field.kind) {
            PartKind.CREDITS -> DecidedField.creditedNames(value)
            PartKind.LIST, PartKind.MBID_LIST -> DecidedField.items(value)
            PartKind.FLAG -> listOfNotNull(value?.takeIf { it == "1" })
            else -> listOfNotNull(value?.takeIf { it.isNotEmpty() })
        }
        properties[key] = when {
            format == TagFileFormat.ID3V23 && field == DecidedField.ORIGINAL_DATE -> values.map { it.take(4) }
            format == TagFileFormat.ID3V23 && values.size > 1 -> listOf(values.joinToString("; "))
            else -> values
        }
    }
    for ((number, total, numberKey, totalKey) in positions) {
        if (number !in decided && total !in decided) continue
        if (format == TagFileFormat.VORBIS) {
            if (number in decided) properties[numberKey] = listOfNotNull(decided[number])
            if (total in decided) properties[totalKey] = listOfNotNull(decided[total])
        } else {
            val old = current[numberKey]?.firstOrNull()?.split('/', limit = 2).orEmpty()
            val n = if (number in decided) decided[number] else old.firstOrNull()
            val count = if (total in decided) decided[total] else old.getOrNull(1)
            if (n == null && count != null && total in decided) unsupported += total
            properties[numberKey] = listOfNotNull(n?.let { if (count == null) it else "$it/$count" })
        }
    }
    return TagProjection(properties, unsupported)
}

private data class PositionFields(
    val number: DecidedField,
    val total: DecidedField,
    val numberKey: String,
    val totalKey: String,
)

private val positions = listOf(
    PositionFields(DecidedField.TRACK_NUMBER, DecidedField.TRACK_TOTAL, "TRACKNUMBER", "TRACKTOTAL"),
    PositionFields(DecidedField.DISC_NUMBER, DecidedField.DISC_TOTAL, "DISCNUMBER", "DISCTOTAL"),
)
private val positionFields = positions.flatMap { listOf(it.number, it.total) }.toSet()

/** Only chosen identification fields can introduce changes. Protect raw tags outside this list. */
private val DecidedField.propertyKey: String?
    get() = when (this) {
        DecidedField.TITLE -> "TITLE"
        DecidedField.ARTIST -> "ARTIST"
        DecidedField.ARTIST_CREDITS -> "ARTISTS"
        DecidedField.ARTIST_SORT -> "ARTISTSORT"
        DecidedField.ALBUM -> "ALBUM"
        DecidedField.ALBUM_ARTIST -> "ALBUMARTIST"
        DecidedField.ALBUM_ARTIST_CREDITS -> "ALBUMARTISTS"
        DecidedField.ALBUM_ARTIST_SORT -> "ALBUMARTISTSORT"
        DecidedField.DATE -> "DATE"
        DecidedField.ORIGINAL_DATE -> "ORIGINALDATE"
        DecidedField.DISC_SUBTITLE -> "DISCSUBTITLE"
        DecidedField.COMPILATION -> "COMPILATION"
        DecidedField.LABEL -> "LABEL"
        DecidedField.CATALOG_NUMBER -> "CATALOGNUMBER"
        DecidedField.BARCODE -> "BARCODE"
        DecidedField.ISRC -> "ISRC"
        DecidedField.RECORDING_MBID -> "MUSICBRAINZ_TRACKID"
        DecidedField.RELEASE_MBID -> "MUSICBRAINZ_ALBUMID"
        DecidedField.RELEASE_GROUP_MBID -> "MUSICBRAINZ_RELEASEGROUPID"
        DecidedField.RELEASE_TRACK_MBID -> "MUSICBRAINZ_RELEASETRACKID"
        DecidedField.ARTIST_IDS -> "MUSICBRAINZ_ARTISTID"
        DecidedField.ALBUM_ARTIST_IDS -> "MUSICBRAINZ_ALBUMARTISTID"
        DecidedField.TRACK_NUMBER, DecidedField.TRACK_TOTAL, DecidedField.DISC_NUMBER,
        DecidedField.DISC_TOTAL, DecidedField.COVER -> null
    }
