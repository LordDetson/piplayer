package io.github.lorddetson.piplayer.library.sort

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/** What the bubble of the scroll thumb shows for a row, by the list's order (docs/library.md §11.2). */
class SectionsTest {

    private val russian = Icu4jCollation(listOf(Locale.forLanguageTag("ru")))

    @Test
    fun `orders by name show the letter of the key`() {
        assertThat(Sections.letter(russian.key("ёлка"), russian.buckets)).isEqualTo("Е")
        assertThat(Sections.letter(russian.key("Beatles, The"), russian.buckets)).isEqualTo("B")
        assertThat(Sections.letter(null, russian.buckets)).isEqualTo("#")
    }

    @Test
    fun `orders by year show the year`() {
        assertThat(Sections.year(1999)).isEqualTo("1999")
        assertThat(Sections.year(null)).isEqualTo(Sections.MISSING)
    }

    @Test
    fun `orders by length show the whole minute`() {
        assertThat(Sections.minutes(185_000)).isEqualTo("3:00")
        assertThat(Sections.minutes(59_999)).isEqualTo("0:00")
        assertThat(Sections.minutes(3_600_000)).isEqualTo("60:00")
        assertThat(Sections.minutes(null)).isEqualTo(Sections.MISSING)
    }

    @Test
    fun `orders by number of songs show the number`() {
        assertThat(Sections.count(12)).isEqualTo("12")
    }
}
