package io.github.lorddetson.piplayer.ui.selection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The queue screen's selection when the queue changes under it (docs/playback.md §9.6). */
class QueueSelectionKeysTest {

    // A file opened twice: two entries of one media ID, numbered by their copy in the play order.
    private val before = listOf("1" to "1", "f" to "f", "2" to "2", "f#2" to "f", "3" to "3")

    @Test
    fun `an unchanged queue keeps every selected entry`() {
        val selection = Selection.Only(setOf("f#2", "2"))

        assertThat(QueueSelectionKeys.afterChange(selection, before, before)).isEqualTo(selection)
    }

    @Test
    fun `after another order the copies of a media ID held twice leave the selection and the others stay`() {
        val shuffled = listOf("3" to "3", "f" to "f", "1" to "1", "f#2" to "f", "2" to "2")

        val after = QueueSelectionKeys.afterChange(Selection.Only(setOf("f#2", "2", "1")), before, shuffled)

        assertThat(after).isEqualTo(Selection.Only(setOf("2", "1")))
    }

    @Test
    fun `a copy removed elsewhere renumbers the other, which leaves the selection too`() {
        val removed = listOf("1" to "1", "2" to "2", "f" to "f", "3" to "3")

        assertThat(QueueSelectionKeys.afterChange(Selection.Only(setOf("f#2")), before, removed)).isNull()
    }

    @Test
    fun `under select all the copies are deselected`() {
        val shuffled = listOf("3" to "3", "f" to "f", "1" to "1", "f#2" to "f", "2" to "2")

        val after = QueueSelectionKeys.afterChange(Selection.AllExcept(setOf("1"), ""), before, shuffled)

        assertThat(after).isEqualTo(Selection.AllExcept(setOf("1", "f", "f#2"), ""))
    }

    @Test
    fun `nothing selected stays nothing`() {
        assertThat(QueueSelectionKeys.afterChange(null, before, before.reversed())).isNull()
    }
}
