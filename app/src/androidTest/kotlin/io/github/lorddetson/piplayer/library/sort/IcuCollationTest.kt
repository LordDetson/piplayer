package io.github.lorddetson.piplayer.library.sort

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.Executors

/** The platform's collator and its letters (docs/library.md §11.1, §11.2), on the device's own ICU. */
@RunWith(AndroidJUnit4::class)
class IcuCollationTest {

    private val russian = IcuCollation(listOf(Locale.forLanguageTag("ru")))
    private val english = IcuCollation(listOf(Locale.ENGLISH))
    private val englishWithRussian = IcuCollation(listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")))
    private val chinese = IcuCollation(listOf(Locale.SIMPLIFIED_CHINESE))

    private val names = listOf(
        "Imagine", "Abba", "abba", "Жуки", "Елка", "Ёжик", "ёлка", "Ежевика", "Аквариум", "арбуз", "Бар", "бар", "Яблоко", "Émilie",
        "Zebra", "10cc", "2 Unlimited", "(I Can't Get No) Satisfaction", "...Baby One More Time", "'Round Midnight", "", "!!!",
        "Αλφα", "東京", "さくら", "Ölfass", "Łódź", "Straße", "Ørsted", "#1 Hits", "Track 2", "Track 10",
        "北京", "上海", "张学友", "周杰伦", "王菲", "阿杜",
    )

    @Test
    fun russianSortsCyrillicFirstWithYoBesideYeDigitsByValueAndPunctuationSkipped() {
        val sorted = listOf("Imagine", "Abba", "Жуки", "Елка", "Ёжик", "Ежевика", "Аквариум", "10cc", "2 Unlimited", "(I Can't Get No) Satisfaction")
            .sortedBy(russian::key)

        assertThat(sorted).containsExactly(
            "2 Unlimited", "10cc", "Аквариум", "Ежевика", "Ёжик", "Елка", "Жуки", "Abba", "(I Can't Get No) Satisfaction", "Imagine",
        )
        assertThat(russian.key("Track 2")).isLessThan(russian.key("Track 10"))
        assertThat(russian.key("бар")).isLessThan(russian.key("Бар"))
    }

    @Test
    fun englishSortsLatinFirst() {
        assertThat(listOf("Жуки", "Abba", "Zebra", "Аквариум").sortedBy(english::key)).containsExactly("Abba", "Zebra", "Аквариум", "Жуки")
    }

    @Test
    fun theNameHoldsTheLanguageTheVersionsAndTheRules() {
        assertThat(russian.name).startsWith("ru/").endsWith("/${SortKeys.RULES_VERSION}")
        assertThat(russian.name.split('/')).hasSize(4)
        assertThat(english.name).startsWith("en/").isNotEqualTo(russian.name)
    }

    @Test
    fun theLettersAreThoseOfTheLanguagesAndEnglish() {
        val labels = russian.buckets.labels

        assertThat(labels.first()).isEqualTo("#")
        assertThat(labels).contains("А", "Е", "Я", "A", "Z")
        assertThat(labels.indexOf("Я")).isLessThan(labels.indexOf("A"))
        assertThat(englishWithRussian.buckets.labels.indexOf("Z")).isLessThan(englishWithRussian.buckets.labels.indexOf("А"))
    }

    @Test
    fun everyLetterFoundFromAKeyIsTheOneTheIndexGivesTheName() {
        for (collation in listOf(russian, english, englishWithRussian, chinese)) {
            for (name in names) {
                assertThat(collation.buckets.labelOf(collation.key(name))).describedAs("${collation.name}: \"$name\"")
                    .isEqualTo(collation.index.getBucket(collation.index.getBucketIndex(name)).label)
            }
        }
        assertThat(russian.buckets.labelOf(russian.key("ёлка"))).isEqualTo("Е")
        assertThat(russian.buckets.labelOf(russian.key("(I Can't Get No) Satisfaction"))).isEqualTo("I")
        assertThat(russian.buckets.labelOf(russian.key("2 Unlimited"))).isEqualTo("#")
        assertThat(russian.buckets.labelOf(russian.key("Αλφα"))).isEqualTo("…")
        assertThat(chinese.buckets.labelOf(chinese.key("北京"))).isEqualTo("B")
    }

    @Test
    fun aChunksOwnKeysEqualTheSharedCollators() {
        val own = russian.newKeys()

        assertThat(names.map(own)).isEqualTo(names.map(russian::key))
    }

    @Test
    fun regionalPreferencesDoNotChangeTheName() {
        assertThat(IcuCollation(listOf(Locale.forLanguageTag("ru-RU-u-fw-mon"))).name).isEqualTo(IcuCollation(listOf(Locale.forLanguageTag("ru-RU"))).name)
    }

    @Test
    fun keysAreTheSameFromManyThreads() {
        val expected = names.map(russian::key)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<List<SortKey>> { (1..50).flatMap { names.map(russian::key) }.takeLast(names.size) } }
            for (result in results) assertThat(result.get()).isEqualTo(expected)
        } finally {
            pool.shutdown()
        }
    }
}
