package io.github.lorddetson.piplayer.library.sort

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The collation that follows the app's languages, and the keys a resolver chunk remembers (docs/library.md §11.1). */
class SortKeysTest {

    @AfterEach
    fun english() {
        SortKeys.use(listOf(Locale.ENGLISH))
    }

    @Test
    fun `another language is another collation, the same one is not`() {
        assertThat(SortKeys.use(listOf(Locale.forLanguageTag("ru")))).isTrue()
        val russian = SortKeys.current().name
        assertThat(SortKeys.use(listOf(Locale.forLanguageTag("ru")))).isFalse()

        assertThat(russian).startsWith("ru/").endsWith("/${SortKeys.RULES_VERSION}")
        assertThat(SortKeys.use(listOf(Locale.ENGLISH))).isTrue()
        assertThat(SortKeys.current().name).startsWith("en/")
    }

    @Test
    fun `regional preferences of the language do not change the collation, its collation keywords do`() {
        SortKeys.use(listOf(Locale.forLanguageTag("ru-RU")))
        val plain = SortKeys.current().name

        assertThat(SortKeys.use(listOf(Locale.forLanguageTag("ru-RU-u-fw-mon-mu-celsius")))).isFalse()
        assertThat(SortKeys.current().name).isEqualTo(plain)
        assertThat(SortKeys.use(listOf(Locale.forLanguageTag("de-DE")))).isTrue()
        val german = SortKeys.current().name
        assertThat(SortKeys.use(listOf(Locale.forLanguageTag("de-DE-u-co-phonebk")))).isTrue()
        assertThat(SortKeys.current().name).isNotEqualTo(german).contains("phonebk")
    }

    @Test
    fun `each chunk's own keys equal the shared collator's`() {
        val collation = Icu4jCollation(listOf(Locale.forLanguageTag("ru")))
        val own = collation.newKeys()
        val names = listOf("Ёжик", "abba", "2 Unlimited", "", "Αλφα", "(I Can't Get No) Satisfaction")

        assertThat(names.map(own)).isEqualTo(names.map(collation::key))
    }

    @Test
    fun `russian sorts cyrillic first, ё with е, digits by value and punctuation skipped`() {
        SortKeys.use(listOf(Locale.forLanguageTag("ru")))
        val names = listOf("Imagine", "Abba", "Жуки", "Елка", "Ёжик", "Ежевика", "Аквариум", "10cc", "2 Unlimited", "(I Can't Get No) Satisfaction")

        assertThat(names.sortedBy(SortKeys::of)).containsExactly(
            "2 Unlimited", "10cc", "Аквариум", "Ежевика", "Ёжик", "Елка", "Жуки", "Abba", "(I Can't Get No) Satisfaction", "Imagine",
        )
    }

    @Test
    fun `a chunk's cache gives the collation's keys and makes each once`() {
        var made = 0
        val counting = object : Collation {
            val inner = Icu4jCollation(listOf(Locale.ENGLISH))
            override val name = inner.name
            override val buckets get() = inner.buckets

            override fun key(text: String): SortKey {
                made++
                return inner.key(text)
            }
        }
        val cache = SortKeys.Cache(counting)

        val keys = listOf("Queen", "Queen", "ABBA", "Queen").map(cache::of)

        assertThat(keys).containsExactly(counting.inner.key("Queen"), counting.inner.key("Queen"), counting.inner.key("ABBA"), counting.inner.key("Queen"))
        assertThat(made).isEqualTo(2)
    }
}
