package io.github.lorddetson.piplayer.ui.selection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The selection of a list's rows (docs/library.md §20.2, §20.3, §20.5, §20.6). */
class SelectionTest {

    private val shown = listOf(1L, 2L, 3L, 4L, 5L)

    @Test
    fun `a first toggle starts a selection of that row, a second deselects it and ends the selection`() {
        val started = null.toggled(3L, shown)
        assertThat(started).isEqualTo(Selection.Only(setOf(3L)))
        assertThat(started!!.isSelected(3L)).isTrue()
        assertThat(started.isSelected(2L)).isFalse()

        val two = started.toggled(1L, shown)
        assertThat(two).isEqualTo(Selection.Only(setOf(3L, 1L)))
        assertThat(two.toggled(1L, shown)!!.toggled(3L, shown)).isNull()
    }

    @Test
    fun `select all is every row but none, which follows the list and counts what it shows`() {
        val all = selectAll<Long>("q")
        assertThat(all).isEqualTo(Selection.AllExcept(emptySet<Long>(), "q"))
        assertThat(all.isWholeList).isTrue()
        assertThat(all.isEverything(shown)).isTrue()
        assertThat(all.count(shown)).isEqualTo(5)
        // A song that joins the list is selected too.
        assertThat(all.isSelected(99L)).isTrue()
        assertThat(all.count(shown + 99L)).isEqualTo(6)
    }

    @Test
    fun `a song deselected after select all stays deselected and the selection is no longer the whole list`() {
        val some = selectAll<Long>("q").toggled(2L, shown)!!
        assertThat(some).isEqualTo(Selection.AllExcept(setOf(2L), "q"))
        assertThat(some.isWholeList).isFalse()
        assertThat(some.isEverything(shown)).isFalse()
        assertThat(some.isSelected(2L)).isFalse()
        assertThat(some.count(shown)).isEqualTo(4)
        assertThat(some.count(shown + 6L)).isEqualTo(5)
    }

    @Test
    fun `deselecting every row of select all ends the selection`() {
        var selection: Selection<Long>? = selectAll("q")
        for (key in shown) selection = selection.toggled(key, shown)
        assertThat(selection).isNull()
    }

    @Test
    fun `only these counts the selected rows the list shows`() {
        val only = Selection.Only(setOf(2L, 4L, 9L))
        assertThat(only.count(shown)).isEqualTo(2)
        assertThat(only.isEverything(shown)).isFalse()
        assertThat(Selection.Only(shown.toSet()).isEverything(shown)).isTrue()
        assertThat(Selection.Only(shown.toSet()).isWholeList).isFalse()
    }

    @Test
    fun `the selected rows go in the order of the list, not the order they were selected in`() {
        val only = null.toggled(5L, shown).toggled(1L, shown).toggled(3L, shown)!!
        assertThat(only.ordered(shown)).containsExactly(1L, 3L, 5L)
        assertThat(selectAll<Long>("q").toggled(4L, shown)!!.ordered(shown)).containsExactly(1L, 2L, 3L, 5L)
    }

    @Test
    fun `a selected song that leaves the list leaves the selection, and nothing left ends it`() {
        val only = Selection.Only(setOf(2L, 4L))
        assertThat(only.retained(listOf(1L, 2L, 3L), "q")).isEqualTo(Selection.Only(setOf(2L)))
        assertThat(only.retained(listOf(1L, 3L), "q")).isNull()
    }

    @Test
    fun `a deselected song that leaves the list makes the selection the whole list again`() {
        val some = Selection.AllExcept(setOf(2L), "q")
        val retained = some.retained(listOf(1L, 3L, 4L), "q")
        assertThat(retained).isEqualTo(Selection.AllExcept(emptySet<Long>(), "q"))
        assertThat(retained!!.isWholeList).isTrue()
    }

    @Test
    fun `select all follows the list to its new name, and an empty list ends it`() {
        assertThat(selectAll<Long>("a").retained(shown, "b")).isEqualTo(Selection.AllExcept(emptySet<Long>(), "b"))
        assertThat(selectAll<Long>("a").retained(emptyList(), "a")).isNull()
        assertThat(Selection.AllExcept(setOf(1L, 2L), "a").retained(listOf(1L, 2L), "a")).isNull()
    }

    @Test
    fun `the saved form keeps the kind, the keys and the list, and gives the same selection back`() {
        val only = Selection.Only(setOf(3L, 1L))
        assertThat(only.saved()!!.selection()).isEqualTo(only)
        val some = Selection.AllExcept(setOf(2L), "search")
        assertThat(some.saved()).isEqualTo(SavedSelection(allExcept = true, keys = listOf(2L), list = "search"))
        assertThat(some.saved()!!.selection()).isEqualTo(some)
    }

    @Test
    fun `more than a thousand keys are not saved`() {
        assertThat(Selection.Only((1L..1_000L).toSet()).saved()).isNotNull()
        assertThat(Selection.Only((1L..1_001L).toSet()).saved()).isNull()
        assertThat(Selection.AllExcept((1L..1_001L).toSet(), "q").saved()).isNull()
        // A whole list saves no keys at all, however long it is.
        assertThat(selectAll<Long>("q").saved()).isNotNull()
    }

    @Test
    fun `a restored all except comes back only on the list it was made on`() {
        val some = Selection.AllExcept(setOf(2L), "chips 7")
        assertThat(some.restoredOn("chips 7")).isEqualTo(some)
        assertThat(some.restoredOn("chips 3")).isNull()
        val only = Selection.Only(setOf(2L))
        assertThat(only.restoredOn("anything")).isEqualTo(only)
    }

    @Test
    fun `the holder starts, counts against the loaded list and ends`() {
        val saved = mutableListOf<SavedSelection<Long>?>()
        val holder = SelectionHolder<Long>(null) { saved += it }
        holder.onList(shown, "q")
        assertThat(holder.state.value).isNull()

        holder.toggle(2L)
        holder.toggle(4L)
        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(2L, 4L)), count = 2, everything = false))
        // Only an "all except" needs the list it was made on.
        assertThat(saved.last()).isEqualTo(SavedSelection(false, listOf(2L, 4L), ""))

        holder.selectAll()
        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.AllExcept(emptySet<Long>(), "q"), count = 5, everything = true))
        holder.toggle(5L)
        assertThat(holder.state.value!!.count).isEqualTo(4)
        assertThat(holder.ordered()).containsExactly(1L, 2L, 3L, 4L)

        holder.end()
        assertThat(holder.state.value).isNull()
        assertThat(saved.last()).isNull()
    }

    @Test
    fun `the holder drops what the next loaded list no longer shows`() {
        val holder = SelectionHolder<Long>(null)
        holder.onList(shown, "q")
        holder.toggle(2L)
        holder.toggle(3L)

        holder.onList(listOf(1L, 3L, 5L), "q")

        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(3L)), count = 1, everything = false))
        holder.onList(listOf(1L), "q")
        assertThat(holder.state.value).isNull()
    }

    @Test
    fun `a restored holder keeps the keys the list still shows and drops an all except of other chips`() {
        val only = SelectionHolder(SavedSelection(false, listOf(2L, 9L), "q"))
        assertThat(only.selecting).isTrue()
        only.onList(shown, "q")
        assertThat(only.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(2L)), count = 1, everything = false))

        val other = SelectionHolder(SavedSelection(true, listOf(2L), "chips 7"))
        other.onList(shown, "chips 3")
        assertThat(other.state.value).isNull()

        val same = SelectionHolder(SavedSelection(true, listOf(2L), "chips 7"))
        same.onList(shown, "chips 7")
        assertThat(same.state.value!!.count).isEqualTo(4)
        // Once restored, the selection follows the chips as usual.
        same.onList(shown + 6L, "chips 3")
        assertThat(same.state.value!!.count).isEqualTo(5)
    }

    @Test
    fun `before the first loaded list a restored all except waits for its count and a tap does not end it`() {
        val holder = SelectionHolder(SavedSelection(true, listOf(2L, 3L), "q"))
        // No list judged yet: the count of an "all except" is not known, and it is not "everything".
        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.AllExcept(setOf(2L, 3L), "q"), count = null, everything = false))

        // A tap on a row of the first page, before the whole list is loaded, deselects it and keeps the selection.
        holder.toggle(1L)
        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.AllExcept(setOf(2L, 3L, 1L), "q"), count = null, everything = false))

        holder.onList(shown, "q")
        assertThat(holder.state.value).isEqualTo(SelectionUi(Selection.AllExcept(setOf(2L, 3L, 1L), "q"), count = 2, everything = false))
    }

    @Test
    fun `before the first loaded list only these counts its keys`() {
        val restored = SelectionHolder(SavedSelection(false, listOf(2L, 9L), ""))
        restored.toggle(4L)
        assertThat(restored.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(2L, 9L, 4L)), count = 3, everything = false))

        // A long press on the first page of a list not loaded yet.
        val fresh = SelectionHolder<Long>(null)
        fresh.toggle(4L)
        assertThat(fresh.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(4L)), count = 1, everything = false))
        fresh.onList(shown, "q")
        assertThat(fresh.state.value).isEqualTo(SelectionUi(Selection.Only(setOf(4L)), count = 1, everything = false))
    }
}
