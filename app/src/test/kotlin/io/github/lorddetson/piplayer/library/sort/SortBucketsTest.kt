package io.github.lorddetson.piplayer.library.sort

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/** The letters of the scroll thumb, found from stored keys (docs/library.md §11.2). */
class SortBucketsTest {

    private val russian = Icu4jCollation(listOf(Locale.forLanguageTag("ru")))
    private val english = Icu4jCollation(listOf(Locale.ENGLISH))
    private val englishWithRussian = Icu4jCollation(listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")))
    private val chinese = Icu4jCollation(listOf(Locale.SIMPLIFIED_CHINESE))

    private fun Icu4jCollation.letter(text: String) = buckets.labelOf(key(text))

    /** Names of every kind: both cases, ё, digits, punctuation, empty, accents, other scripts. */
    private val names = listOf(
        "Арбуз", "арбуз", "Бар", "бар", "Ёжик", "ёлка", "Ежевика", "Елка", "Жуки", "Яблоко", "ящик", "Ъ", "Abba", "abba", "Émilie",
        "Zebra", "zz top", "2 Unlimited", "10cc", "(I Can't Get No) Satisfaction", "...Baby One More Time", "'Round Midnight",
        "", "!!!", "  ", "Αλφα", "ωμέγα", "東京", "さくら", "Ölfass", "Ünal", "Łódź", "Straße", "Ørsted", "#1 Hits",
        "北京", "上海", "张学友", "周杰伦", "王菲", "阿杜",
    )

    @Test
    fun `russian letters come before latin ones, with digits and symbols under hash first`() {
        val labels = russian.buckets.labels

        assertThat(labels.first()).isEqualTo(SortBuckets.UNDERFLOW)
        assertThat(labels).contains("А", "Е", "Я", "A", "Z")
        assertThat(labels).doesNotContain("Ё")
        assertThat(labels.indexOf("Я")).isLessThan(labels.indexOf("A"))
        assertThat(english.buckets.labels.indexOf("Z")).isLessThan(englishWithRussian.buckets.labels.indexOf("А"))
    }

    @Test
    fun `a letter is found from the key whatever the case, and ё is with е`() {
        assertThat(russian.letter("бар")).isEqualTo("Б")
        assertThat(russian.letter("Бар")).isEqualTo("Б")
        assertThat(russian.letter("ёлка")).isEqualTo("Е")
        assertThat(russian.letter("Ёжик")).isEqualTo("Е")
        assertThat(russian.letter("ящик")).isEqualTo("Я")
        assertThat(russian.letter("Zebra")).isEqualTo("Z")
        assertThat(russian.letter("abba")).isEqualTo("A")
        assertThat(russian.letter("Émilie")).isEqualTo("E")
    }

    @Test
    fun `digits, symbols and empty names go under hash, punctuation in front is skipped`() {
        assertThat(russian.letter("2 Unlimited")).isEqualTo("#")
        assertThat(russian.letter("10cc")).isEqualTo("#")
        assertThat(russian.letter("")).isEqualTo("#")
        assertThat(russian.letter("!!!")).isEqualTo("#")
        assertThat(russian.buckets.labelOf(null)).isEqualTo("#")
        assertThat(russian.letter("(I Can't Get No) Satisfaction")).isEqualTo("I")
        assertThat(russian.letter("...Baby One More Time")).isEqualTo("B")
    }

    @Test
    fun `scripts without letters of their own share one bucket, also between two scripts with letters`() {
        assertThat(russian.letter("Αλφα")).isEqualTo(SortBuckets.OTHER)
        assertThat(russian.letter("東京")).isEqualTo(SortBuckets.OTHER)
        assertThat(english.letter("Ёжик")).isEqualTo(SortBuckets.OTHER)
        // English with Russian: Greek sorts between Latin and Cyrillic.
        assertThat(englishWithRussian.letter("Αλφα")).isEqualTo(SortBuckets.OTHER)
        assertThat(englishWithRussian.letter("Ёжик")).isEqualTo("Е")
        assertThat(englishWithRussian.letter("Zebra")).isEqualTo("Z")
    }

    @Test
    fun `every letter found from a key is the one ICU's own index gives the name`() {
        for (collation in listOf(russian, english, englishWithRussian, chinese)) {
            for (name in names) {
                assertThat(collation.letter(name)).describedAs("${collation.name}: \"$name\"")
                    .isEqualTo(collation.index.getBucket(collation.index.getBucketIndex(name)).label)
            }
        }
    }

    @Test
    fun `the letters follow the order of the keys`() {
        for (collation in listOf(russian, englishWithRussian, chinese)) {
            val sorted = names.sortedBy { collation.key(it) }
            val indices = sorted.map { collation.buckets.indexOf(collation.key(it)) }
            assertThat(indices).describedAs(collation.name).isSorted()
        }
    }

    @Test
    fun `chinese names go under the latin letter of their pinyin`() {
        assertThat(chinese.letter("北京")).isEqualTo("B")
        assertThat(chinese.letter("上海")).isEqualTo("S")
        assertThat(chinese.letter("张学友")).isEqualTo("Z")
    }

    @Test
    fun `without a collation everything is under hash`() {
        assertThat(SortBuckets.NONE.labelOf(russian.key("Арбуз"))).isEqualTo("#")
    }
}
