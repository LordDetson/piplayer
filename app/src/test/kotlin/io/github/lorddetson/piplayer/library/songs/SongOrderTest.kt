package io.github.lorddetson.piplayer.library.songs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SongOrderTest {

    private fun song(id: Long, title: String) = Song(id, "file:///$id.mp3", title, null, null, null)

    private fun titles(vararg songs: Song) = SongOrder.byTitle(songs.toList()).map { it.title }

    @Test
    fun `case and diacritics do not change the order`() {
        assertThat(titles(song(1, "beyoncé"), song(2, "Alpha"), song(3, "BEYONCE B"), song(4, "Ángel")))
            .containsExactly("Alpha", "Ángel", "beyoncé", "BEYONCE B")
    }

    @Test
    fun `yo sorts as ye`() {
        assertThat(titles(song(1, "Ёлка"), song(2, "Ежик"), song(3, "Жук")))
            .containsExactly("Ежик", "Ёлка", "Жук")
    }

    @Test
    fun `digits come before Latin and Latin before Cyrillic`() {
        assertThat(titles(song(1, "Звезда"), song(2, "Zero"), song(3, "99 Luftballons"), song(4, "Apple")))
            .containsExactly("99 Luftballons", "Apple", "Zero", "Звезда")
    }

    @Test
    fun `punctuation counts as a space`() {
        assertThat(titles(song(1, "AB"), song(2, "A-C"), song(3, "A B")))
            .containsExactly("A B", "A-C", "AB")
    }

    @Test
    fun `equal titles keep the order of their IDs`() {
        val sorted = SongOrder.byTitle(listOf(song(9, "Same"), song(3, "same"), song(5, "SAME")))

        assertThat(sorted.map { it.id }).containsExactly(3L, 5L, 9L)
    }
}
