package io.github.lorddetson.piplayer.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The kept orders of the Genres tab and the genre pages (docs/library.md §6.3, §19.2, §19.3). */
class ListSortsTest {

    @Test
    fun `the Genres tab sorts by name and the genre pages by artist by default`() {
        val empty = mutablePreferencesOf()

        assertThat(ListSorts.orderOf(empty, ListName.GENRES)).isEqualTo(ListOrder("name"))
        assertThat(ListSorts.orderOf(empty, ListName.GENRE_PAGE)).isEqualTo(ListOrder("artist"))
        assertThat(ListName.GENRES.id).isEqualTo("genres")
        assertThat(ListName.GENRE_PAGE.id).isEqualTo("genre_page")
        assertThat(ListName.GENRES.offersGrid).isFalse()
    }

    @Test
    fun `a stored field of the list is kept with its reverse`() {
        val stored = mutablePreferencesOf(
            ListSorts.fieldKey(ListName.GENRES) to "songs",
            ListSorts.reversedKey(ListName.GENRES) to true,
            ListSorts.fieldKey(ListName.GENRE_PAGE) to "length",
        )

        assertThat(ListSorts.orderOf(stored, ListName.GENRES)).isEqualTo(ListOrder("songs", reversed = true))
        assertThat(ListSorts.orderOf(stored, ListName.GENRE_PAGE)).isEqualTo(ListOrder("length"))
        for (field in listOf("artist", "title", "album", "length")) {
            val page = mutablePreferencesOf(ListSorts.fieldKey(ListName.GENRE_PAGE) to field)
            assertThat(ListSorts.orderOf(page, ListName.GENRE_PAGE).field).isEqualTo(field)
        }
    }

    @Test
    fun `a stored field the list does not offer falls back to its default`() {
        val stored = mutablePreferencesOf(
            ListSorts.fieldKey(ListName.GENRES) to "year",
            ListSorts.fieldKey(ListName.GENRE_PAGE) to "track",
        )

        assertThat(ListSorts.orderOf(stored, ListName.GENRES).field).isEqualTo("name")
        assertThat(ListSorts.orderOf(stored, ListName.GENRE_PAGE).field).isEqualTo("artist")
    }
}
