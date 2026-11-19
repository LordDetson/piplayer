package io.github.lorddetson.piplayer.ui.identification

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Edit fields' texts and the parts their edit decision stores (TI §6, review screen B; §7.1, §10.2). */
class FieldEditsTest {

    @Test
    fun `the editor offers the fields the review proposes, not the flag, the IDs or the cover`() {
        assertThat(FieldEdits.EDITABLE).doesNotContain(
            ProtectedField.COMPILATION, ProtectedField.MUSICBRAINZ_IDS, ProtectedField.COVER, ProtectedField.MANUAL_TAGS,
        )
        assertThat(FieldEdits.EDITABLE).containsExactlyElementsOf(
            ProtectedField.identificationFields - setOf(ProtectedField.COMPILATION, ProtectedField.MUSICBRAINZ_IDS, ProtectedField.COVER),
        )
    }

    @Test
    fun `an entry shows a value's text, a number with its total, or nothing`() {
        assertThat(FieldEdits.entry(ProtectedField.TITLE, FieldValue.Text("Paper Lanterns")))
            .isEqualTo(EditorEntry(ProtectedField.TITLE, "Paper Lanterns"))
        assertThat(FieldEdits.entry(ProtectedField.TRACK_NUMBER, FieldValue.OfTotal(3, 11)))
            .isEqualTo(EditorEntry(ProtectedField.TRACK_NUMBER, "3", "11"))
        assertThat(FieldEdits.entry(ProtectedField.DISC_NUMBER, FieldValue.OfTotal(1, null)))
            .isEqualTo(EditorEntry(ProtectedField.DISC_NUMBER, "1", ""))
        assertThat(FieldEdits.entry(ProtectedField.ALBUM, null)).isEqualTo(EditorEntry(ProtectedField.ALBUM, ""))
    }

    @Test
    fun `texts are trimmed, an emptied field clears it and lists are separated by semicolons`() {
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.TITLE, "  Paper Lanterns "))).isEqualTo(ParsedEntry.Value(FieldValue.Text("Paper Lanterns")))
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.TITLE, "   "))).isEqualTo(ParsedEntry.Value(null))
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.LABEL, " Harbor ;Tide Records;; ")))
            .isEqualTo(ParsedEntry.Value(FieldValue.Text("Harbor; Tide Records")))
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.ISRC, " ; "))).isEqualTo(ParsedEntry.Value(null))
    }

    @Test
    fun `a number takes digits only, with an optional total`() {
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.TRACK_NUMBER, " 3 ", "11"))).isEqualTo(ParsedEntry.Value(FieldValue.OfTotal(3, 11)))
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.TRACK_NUMBER, "3", ""))).isEqualTo(ParsedEntry.Value(FieldValue.OfTotal(3, null)))
        assertThat(FieldEdits.parse(EditorEntry(ProtectedField.DISC_NUMBER, "", ""))).isEqualTo(ParsedEntry.Value(null))
        for (entry in listOf(
            EditorEntry(ProtectedField.TRACK_NUMBER, "three", ""),
            EditorEntry(ProtectedField.TRACK_NUMBER, "3", "x"),
            EditorEntry(ProtectedField.TRACK_NUMBER, "", "11"),
            EditorEntry(ProtectedField.TRACK_NUMBER, "0", ""),
            EditorEntry(ProtectedField.TRACK_NUMBER, "-2", ""),
            EditorEntry(ProtectedField.DISC_NUMBER, "1", "0"),
            EditorEntry(ProtectedField.DISC_NUMBER, "99999999999", ""),
        )) {
            assertThat(FieldEdits.parse(entry)).describedAs(entry.toString()).isEqualTo(ParsedEntry.Invalid)
        }
    }

    @Test
    fun `an edit stores each field's parts, a number with its total and a list as items`() {
        val parts = FieldEdits.parts(
            mapOf(
                ProtectedField.TITLE to FieldValue.Text("Paper Lanterns"),
                ProtectedField.ARTIST to FieldValue.Text("The Quiet Harbors"),
                ProtectedField.TRACK_NUMBER to FieldValue.OfTotal(3, 11),
                ProtectedField.DISC_NUMBER to FieldValue.OfTotal(2, null),
                ProtectedField.LABEL to FieldValue.Text("Harbor; Tide Records"),
                ProtectedField.ISRC to FieldValue.Text("XXA001900003"),
            ),
        )
        assertThat(parts).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                DecidedField.TITLE to "Paper Lanterns",
                DecidedField.ARTIST to "The Quiet Harbors",
                DecidedField.TRACK_NUMBER to "3",
                DecidedField.TRACK_TOTAL to "11",
                DecidedField.DISC_NUMBER to "2",
                DecidedField.DISC_TOTAL to null,
                DecidedField.LABEL to "Harbor${DecidedField.SEPARATOR}Tide Records",
                DecidedField.ISRC to "XXA001900003",
            ),
        )
    }

    @Test
    fun `an emptied field stores its parts empty`() {
        assertThat(FieldEdits.parts(mapOf(ProtectedField.ALBUM to null, ProtectedField.TRACK_NUMBER to null)))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(DecidedField.ALBUM to null, DecidedField.TRACK_NUMBER to null, DecidedField.TRACK_TOTAL to null),
            )
    }

    @Test
    fun `every editable field stores parts of its own group only`() {
        for (field in FieldEdits.EDITABLE) {
            val value = if (FieldEdits.hasTotal(field)) FieldValue.OfTotal(1, 2) else FieldValue.Text("x")
            val parts = FieldEdits.parts(mapOf(field to value))
            assertThat(parts).describedAs(field.name).isNotEmpty()
            assertThat(parts.keys.map { it.group }.toSet()).describedAs(field.name).containsExactly(field)
        }
    }
}
