package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.ProtectedField

/** Converts the changed properties the library models to whole-field user decisions. */
internal fun manualLibraryEdit(
    current: Map<String, List<String>>,
    changes: Map<String, TagChange>,
): Map<DecidedField, String?>? {
    if (changes.isEmpty()) return null
    val groups = changes.keys.mapTo(HashSet()) { libraryField[it] ?: return null }
    val edited = current.toMutableMap().apply {
        for ((key, alias) in artistAliases) if (key in changes && alias !in changes) remove(alias)
        for ((key, change) in changes) {
            if (change.after.isEmpty()) remove(key) else put(key, change.after)
        }
    }
    val parts = DecisionContent.ofTags(TrackTags.from(edited)).toMutableMap()
    for ((key, part) in positionParts) {
        val change = changes[key] ?: continue
        if (change.after.isEmpty()) {
            parts[part] = null
            continue
        }
        val value = change.after.singleOrNull() ?: return null
        val number = value.substringBefore('/').positiveNumber() ?: return null
        parts[part] = number
        if ('/' in value) {
            val totalKey = positionTotals[key] ?: return null
            val total = value.substringAfter('/').positiveNumber() ?: return null
            if (totalKey !in changes) parts[positionParts.getValue(totalKey)] = total
        }
    }
    for (part in musicBrainzParts) {
        val values = if (part == DecidedField.ARTIST_IDS || part == DecidedField.ALBUM_ARTIST_IDS) DecidedField.items(parts[part])
        else listOfNotNull(parts[part])
        if (part.group in groups && values.any { Hex.mbidBytes(it) == null }) return null
    }
    return parts.filterKeys { it.group in groups }
}

private val artistAliases = listOf(
    "ARTIST" to "ARTISTS", "ALBUMARTIST" to "ALBUMARTISTS", "ALBUMARTIST" to "ALBUM ARTIST",
    "ALBUM ARTIST" to "ALBUMARTIST", "ALBUM ARTIST" to "ALBUMARTISTS",
)

private val musicBrainzParts = DecidedField.partsOf(ProtectedField.MUSICBRAINZ_IDS)

private val libraryField = mapOf(
    "TITLE" to ProtectedField.TITLE,
    "ARTIST" to ProtectedField.ARTIST,
    "ARTISTS" to ProtectedField.ARTIST,
    "ARTISTSORT" to ProtectedField.ARTIST,
    "ALBUM" to ProtectedField.ALBUM,
    "ALBUMARTIST" to ProtectedField.ALBUM_ARTIST,
    "ALBUM ARTIST" to ProtectedField.ALBUM_ARTIST,
    "ALBUMARTISTS" to ProtectedField.ALBUM_ARTIST,
    "ALBUMARTISTSORT" to ProtectedField.ALBUM_ARTIST,
    "DATE" to ProtectedField.DATE,
    "ORIGINALDATE" to ProtectedField.ORIGINAL_DATE,
    "TRACKNUMBER" to ProtectedField.TRACK_NUMBER,
    "TRACKTOTAL" to ProtectedField.TRACK_NUMBER,
    "DISCNUMBER" to ProtectedField.DISC_NUMBER,
    "DISCTOTAL" to ProtectedField.DISC_NUMBER,
    "DISCSUBTITLE" to ProtectedField.DISC_SUBTITLE,
    "COMPILATION" to ProtectedField.COMPILATION,
    "LABEL" to ProtectedField.LABEL,
    "CATALOGNUMBER" to ProtectedField.CATALOG_NUMBER,
    "BARCODE" to ProtectedField.BARCODE,
    "ISRC" to ProtectedField.ISRC,
    "MUSICBRAINZ_TRACKID" to ProtectedField.MUSICBRAINZ_IDS,
    "MUSICBRAINZ_ALBUMID" to ProtectedField.MUSICBRAINZ_IDS,
    "MUSICBRAINZ_RELEASEGROUPID" to ProtectedField.MUSICBRAINZ_IDS,
    "MUSICBRAINZ_RELEASETRACKID" to ProtectedField.MUSICBRAINZ_IDS,
    "MUSICBRAINZ_ARTISTID" to ProtectedField.MUSICBRAINZ_IDS,
    "MUSICBRAINZ_ALBUMARTISTID" to ProtectedField.MUSICBRAINZ_IDS,
)

private val positionParts = mapOf(
    "TRACKNUMBER" to DecidedField.TRACK_NUMBER, "TRACKTOTAL" to DecidedField.TRACK_TOTAL,
    "DISCNUMBER" to DecidedField.DISC_NUMBER, "DISCTOTAL" to DecidedField.DISC_TOTAL,
)

private val positionTotals = mapOf("TRACKNUMBER" to "TRACKTOTAL", "DISCNUMBER" to "DISCTOTAL")

private fun String.positiveNumber(): String? = trim().toIntOrNull()?.takeIf { it > 0 }?.toString()
