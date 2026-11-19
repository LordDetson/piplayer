package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagDiffTest {
    @Test
    fun `an accepted value from ID3v1 is written to ID3v2 before removing the fallback`() {
        val selected = TagFileSelection(mapOf(DecidedField.TITLE to "Keep this title"), frontCovers = null)
        val properties = mapOf("TITLE" to listOf("Keep this title"), "ALBUM" to listOf("Other ID3v1 field"))

        val diff = selected.diff(TagFileFormat.ID3V24, properties, withoutRemovedId3v1 = emptyMap())

        assertThat(diff.changes).containsExactlyEntriesOf(mapOf("TITLE" to TagChange(emptyList(), listOf("Keep this title"))))
        assertThat(diff.properties).containsOnlyKeys("TITLE")
    }

    @Test
    fun `a partial edit keeps the original strings of every untouched property`() {
        val current = mapOf("TITLE" to listOf("Old"), "ARTISTS" to listOf("a", "b"), "CUSTOM" to listOf("e\u0301"))

        val diff = TagDiff.between(current, mapOf("TITLE" to listOf("Cafe\u0301")))

        assertThat(diff.properties).isEqualTo(current + ("TITLE" to listOf("Café")))
        assertThat(diff.changes).containsExactlyEntriesOf(
            mapOf("TITLE" to TagChange(listOf("Old"), listOf("Café"))),
        )
    }

    @Test
    fun `explicitly cleared keys are removed and unchanged choices produce no diff`() {
        val current = mapOf("TITLE" to listOf("Old"), "ARTIST" to listOf("Same"))

        val diff = TagDiff.between(current, mapOf("TITLE" to emptyList(), "ARTIST" to listOf("Same")))

        assertThat(diff.properties).isEqualTo(mapOf("ARTIST" to listOf("Same")))
        assertThat(diff.changes).isEqualTo(mapOf("TITLE" to TagChange(listOf("Old"), emptyList())))
    }

    @Test
    fun `fill-empty choices keep populated fields and apply empty fields`() {
        val diff = TagDiff.between(
            mapOf("GENRE" to listOf("Rock"), "COMMENT" to listOf("")),
            mapOf("GENRE" to listOf("Pop"), "COMMENT" to listOf("New")),
            fillEmptyOnly = setOf("GENRE", "COMMENT"),
        )

        assertThat(diff.properties).isEqualTo(mapOf("GENRE" to listOf("Rock"), "COMMENT" to listOf("New")))
        assertThat(diff.changes.keys).containsExactly("COMMENT")
    }

    @Test
    fun `undo restores only keys still holding the written values`() {
        val current = mapOf("TITLE" to listOf("Externally edited"), "ARTIST" to listOf("New artist"))
        val written = mapOf(
            "TITLE" to TagChange(listOf("Old title"), listOf("New title")),
            "ARTIST" to TagChange(emptyList(), listOf("New artist")),
            "COMMENT" to TagChange(listOf("Old comment"), emptyList()),
        )

        val undo = TagDiff.undo(current, written)

        assertThat(undo.properties).isEqualTo(
            mapOf("TITLE" to listOf("Externally edited"), "COMMENT" to listOf("Old comment")),
        )
        assertThat(undo.conflicts).containsExactly("TITLE")
    }
}
