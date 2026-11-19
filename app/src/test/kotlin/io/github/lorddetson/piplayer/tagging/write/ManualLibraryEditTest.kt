package io.github.lorddetson.piplayer.tagging.write

import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ManualLibraryEditTest {
    @Test
    fun `an artist edit replaces the old credits and preserves its sort name`() {
        val current = mapOf("ARTIST" to listOf("Old"), "ARTISTS" to listOf("Old", "Guest"), "ARTISTSORT" to listOf("Sort"))
        val names = listOf("First", "Second")
        val values = manualLibraryEdit(current, mapOf("ARTIST" to TagChange(current.getValue("ARTIST"), names)))

        assertThat(values).isEqualTo(mapOf(
            DecidedField.ARTIST to "First; Second",
            DecidedField.ARTIST_CREDITS to DecisionContent.tagCredits(names),
            DecidedField.ARTIST_SORT to "Sort",
        ))
    }

    @Test
    fun `a local edit applies the selected field and can clear it`() {
        val current = mapOf("TITLE" to listOf("Old"), "ALBUM" to listOf("Album"))
        assertThat(manualLibraryEdit(current, mapOf("TITLE" to TagChange(current.getValue("TITLE"), emptyList()))))
            .isEqualTo(mapOf(DecidedField.TITLE to null))
    }

    @Test
    fun `a number and total are saved as shown and an explicit clear wins over the old ratio`() {
        val current = mapOf("TRACKNUMBER" to listOf("2/8"), "TRACKTOTAL" to listOf("8"))
        assertThat(manualLibraryEdit(current, mapOf("TRACKNUMBER" to TagChange(listOf("2/8"), listOf("3/9")))))
            .isEqualTo(mapOf(DecidedField.TRACK_NUMBER to "3", DecidedField.TRACK_TOTAL to "9"))
        assertThat(manualLibraryEdit(current, mapOf("TRACKTOTAL" to TagChange(listOf("8"), emptyList()))))
            .isEqualTo(mapOf(DecidedField.TRACK_NUMBER to "2", DecidedField.TRACK_TOTAL to null))
    }

    @Test
    fun `a partial save never discards a field the library cannot store`() {
        val title = TagChange(emptyList(), listOf("New"))
        val comment = TagChange(emptyList(), listOf("Note"))
        val number = TagChange(emptyList(), listOf("B3"))
        assertThat(manualLibraryEdit(emptyMap(), mapOf("TITLE" to title, "COMMENT" to comment))).isNull()
        assertThat(manualLibraryEdit(emptyMap(), mapOf("TRACKNUMBER" to number))).isNull()
    }
}
