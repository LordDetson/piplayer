package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField

/** One field of Edit fields (TI §6, review screen B): its [text] and, for a track or disc number, its [total]. */
data class EditorEntry(val field: ProtectedField, val text: String, val total: String = "")

/** What an entry of Edit fields reads as. */
sealed interface ParsedEntry {

    /** The entry's [value]; null for an emptied field, which clears it. */
    data class Value(val value: FieldValue?) : ParsedEntry

    /** A number or total that is not one: Save waits. */
    data object Invalid : ParsedEntry
}

/** The fields Edit fields offers and what its texts mean (TI §6, review screen B; §7.1). */
object FieldEdits {

    /** The fields the review can propose, in the protected-fields list's order. */
    val EDITABLE: List<ProtectedField> = listOf(
        ProtectedField.TITLE,
        ProtectedField.ARTIST,
        ProtectedField.ALBUM,
        ProtectedField.ALBUM_ARTIST,
        ProtectedField.DATE,
        ProtectedField.ORIGINAL_DATE,
        ProtectedField.TRACK_NUMBER,
        ProtectedField.DISC_NUMBER,
        ProtectedField.DISC_SUBTITLE,
        ProtectedField.LABEL,
        ProtectedField.CATALOG_NUMBER,
        ProtectedField.BARCODE,
        ProtectedField.ISRC,
    )

    /** How the diff shows a list's items: a list the user leaves as it was equals the release's value. */
    private const val LIST_SEPARATOR = ReviewDiff.LIST_SEPARATOR

    /** A track or disc number, which has a total beside it. */
    fun hasTotal(field: ProtectedField): Boolean = field == ProtectedField.TRACK_NUMBER || field == ProtectedField.DISC_NUMBER

    /** A list of values separated by ";". */
    fun isList(field: ProtectedField): Boolean =
        field == ProtectedField.LABEL || field == ProtectedField.CATALOG_NUMBER || field == ProtectedField.ISRC

    /** The entry of [field] that shows [value]. */
    fun entry(field: ProtectedField, value: FieldValue?): EditorEntry = when (value) {
        is FieldValue.Text -> EditorEntry(field, value.text)
        is FieldValue.OfTotal -> EditorEntry(field, value.number.toString(), value.total?.toString().orEmpty())
        // No editable field holds a flag or IDs.
        is FieldValue.Flag, is FieldValue.Ids, null -> EditorEntry(field, "")
    }

    /** What [entry] reads as. */
    fun parse(entry: EditorEntry): ParsedEntry {
        val field = entry.field
        if (hasTotal(field)) {
            val number = entry.text.trim()
            val total = entry.total.trim()
            if (number.isEmpty()) return if (total.isEmpty()) ParsedEntry.Value(null) else ParsedEntry.Invalid
            val n = positive(number) ?: return ParsedEntry.Invalid
            val t = if (total.isEmpty()) null else positive(total) ?: return ParsedEntry.Invalid
            return ParsedEntry.Value(FieldValue.OfTotal(n, t))
        }
        if (isList(field)) return ParsedEntry.Value(items(entry.text).takeIf { it.isNotEmpty() }?.let { FieldValue.Text(it.joinToString(LIST_SEPARATOR)) })
        return ParsedEntry.Value(entry.text.trim().takeIf { it.isNotEmpty() }?.let(FieldValue::Text))
    }

    /** The parts an edit decision of [edits] stores (TI §7.1, §10.2), each field with its main part at least. */
    fun parts(edits: Map<ProtectedField, FieldValue?>): Map<DecidedField, String?> = buildMap {
        for ((field, value) in edits) {
            val main = DecidedField.partsOf(field).first()
            when {
                hasTotal(field) -> {
                    val numbers = value as? FieldValue.OfTotal
                    put(main, numbers?.number?.toString())
                    put(if (field == ProtectedField.TRACK_NUMBER) DecidedField.TRACK_TOTAL else DecidedField.DISC_TOTAL, numbers?.total?.toString())
                }
                isList(field) -> put(main, DecidedField.list(items((value as? FieldValue.Text)?.text.orEmpty())))
                else -> put(main, (value as? FieldValue.Text)?.text)
            }
        }
    }

    private fun items(text: String): List<String> = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** A whole number above 0 in ASCII digits, as a tag holds it. */
    private fun positive(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
}
