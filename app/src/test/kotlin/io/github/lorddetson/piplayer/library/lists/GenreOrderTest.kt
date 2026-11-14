package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.sort.SortKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The order of the Genres list with "Unknown genre" last (docs/library.md §19.2). */
class GenreOrderTest {

    private fun genre(name: String, songs: Int) = GenreGroup(GenreKeys.of(name), name, songs, sortKey = SortKeys.of(name))

    /** A real genre named like the row of the songs without one, which sorts by its name among the others. */
    private val namedUnknown = genre("Unknown genre", 2)
    private val genres = listOf(genre("Rock", 5), genre("Ambient", 1), namedUnknown, genre("Jazz", 7))

    private fun order(sort: GenreSort, reversed: Boolean = false, unknown: Int = 3, searching: Boolean = false) =
        GenreOrder.of(genres, unknown, sort, reversed, searching) { key -> key?.let { "L" + it.bytes.firstOrNull() } }

    @Test
    fun `by name the genres sort by their names and the songs without one come last`() {
        val list = order(GenreSort.NAME)

        assertThat(list.map { it.name }).containsExactly("Ambient", "Jazz", "Rock", "Unknown genre", "")
        assertThat(list.last().unknown).isTrue()
        assertThat(list.last().key).isEqualTo(GenreKeys.NONE)
        assertThat(list.last().songs).isEqualTo(3)
        // The real "Unknown genre" keeps its own key and place.
        assertThat(list[3].unknown).isFalse()
        assertThat(list[3].key).isEqualTo(namedUnknown.key)
    }

    @Test
    fun `the reverse keeps the songs without a genre last`() {
        assertThat(order(GenreSort.NAME, reversed = true).map { it.name }).containsExactly("Unknown genre", "Rock", "Jazz", "Ambient", "")
        assertThat(order(GenreSort.SONGS).map { it.name }).containsExactly("Jazz", "Rock", "Unknown genre", "Ambient", "")
        assertThat(order(GenreSort.SONGS, reversed = true).map { it.name }).containsExactly("Ambient", "Unknown genre", "Rock", "Jazz", "")
    }

    @Test
    fun `ties of the number of songs go by name`() {
        val tied = listOf(genre("Pop", 2), genre("Blues", 2), genre("Metal", 9))

        assertThat(GenreOrder.of(tied, 0, GenreSort.SONGS, false, false) { null }.map { it.name }).containsExactly("Metal", "Blues", "Pop")
    }

    @Test
    fun `sections are letters by name and numbers by count, and the songs without a genre take the one before`() {
        val byName = order(GenreSort.NAME)
        assertThat(byName.dropLast(1).map { it.section }).isEqualTo(byName.dropLast(1).map { "L" + it.sortKey!!.bytes.first() })
        assertThat(byName.last().section).isEqualTo(byName[byName.size - 2].section)

        val byCount = order(GenreSort.SONGS)
        assertThat(byCount.map { it.section }).containsExactly("7", "5", "2", "1", "1")
    }

    @Test
    fun `the songs without a genre show only when there are some and never while searching`() {
        assertThat(order(GenreSort.NAME, unknown = 0).map { it.name }).containsExactly("Ambient", "Jazz", "Rock", "Unknown genre")
        assertThat(order(GenreSort.NAME, searching = true).none { it.unknown }).isTrue()
    }

    @Test
    fun `in a library without any genre the songs without one are the only row, with no section`() {
        val list = GenreOrder.of(emptyList(), 12, GenreSort.NAME, false, false) { "X" }

        assertThat(list).hasSize(1)
        assertThat(list.single().unknown).isTrue()
        assertThat(list.single().songs).isEqualTo(12)
        assertThat(list.single().section).isNull()
    }
}
