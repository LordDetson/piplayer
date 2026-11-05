package io.github.lorddetson.piplayer.library.songs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A list that keeps its order while the tag pass changes titles (docs/library.md §1.4). */
class FrozenOrderTest {

    private fun song(id: Long, title: String = "Song $id") = Song(id, "uri$id", title, null, null, null)
    private fun ids(songs: List<Song>) = songs.map { it.id }

    @Test
    fun `rows keep their rank whatever order the fresh read has`() {
        val kept = listOf(song(1), song(2), song(3))
        val fresh = listOf(song(3), song(1), song(2))

        assertThat(ids(FrozenOrder.merge(kept, fresh))).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `rows take the values of the fresh read`() {
        val merged = FrozenOrder.merge(listOf(song(1, "Old")), listOf(song(1, "New")))

        assertThat(merged.single().title).isEqualTo("New")
    }

    @Test
    fun `a removed row goes`() {
        assertThat(ids(FrozenOrder.merge(listOf(song(1), song(2), song(3)), listOf(song(1), song(3))))).containsExactly(1L, 3L)
    }

    @Test
    fun `a new row goes after the row before it in the fresh read`() {
        val kept = listOf(song(1), song(2), song(3))
        val fresh = listOf(song(1), song(2), song(9), song(3))

        assertThat(ids(FrozenOrder.merge(kept, fresh))).containsExactly(1L, 2L, 9L, 3L)
    }

    @Test
    fun `a new row follows its kept neighbour wherever that one stays`() {
        // Row 3 keeps its first place; in the fresh order row 9 comes right after it.
        val kept = listOf(song(3), song(1), song(2))
        val fresh = listOf(song(1), song(2), song(3), song(9))

        assertThat(ids(FrozenOrder.merge(kept, fresh))).containsExactly(3L, 9L, 1L, 2L)
    }

    @Test
    fun `a new row first in the fresh read goes first`() {
        assertThat(ids(FrozenOrder.merge(listOf(song(1)), listOf(song(9), song(1))))).containsExactly(9L, 1L)
    }

    @Test
    fun `new rows in a row keep their order`() {
        val merged = FrozenOrder.merge(listOf(song(1), song(2)), listOf(song(1), song(8), song(9), song(2)))

        assertThat(ids(merged)).containsExactly(1L, 8L, 9L, 2L)
    }

    @Test
    fun `nothing kept is the fresh read`() {
        assertThat(ids(FrozenOrder.merge(emptyList(), listOf(song(2), song(1))))).containsExactly(2L, 1L)
    }
}
