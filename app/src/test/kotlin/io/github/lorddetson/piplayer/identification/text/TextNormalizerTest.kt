package io.github.lorddetson.piplayer.identification.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TextNormalizerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        textBlock = """
        Another LOVE        | another love
        Beyoncé             | beyonce
        Motörhead           | motorhead
        Sigur Rós           | sigur ros
        ÉTÉ                 | ete
        Ёжик                | ежик
        ещё раз             | еще раз
        Мой край            | мой край
        Ўсё                 | ўсе
        Би-2                | би 2
        AC/DC               | ac dc
        Idea 22             | idea 22
        東京                 | 東京""",
    )
    fun `normalizes case, diacritics and punctuation`(input: String, expected: String) {
        assertThat(TextNormalizer.normalize(input)).isEqualTo(expected)
    }

    @Test
    fun `keeps the Cyrillic short i and short u, which are letters of their own`() {
        assertThat(TextNormalizer.normalize("Й й Ў ў")).isEqualTo("й й ў ў")
    }

    @Test
    fun `keeps kana, Han and Hangul whole, so voiced kana keep their marks`() {
        assertThat(TextNormalizer.normalize("ドラゴンボール")).isEqualTo("ドラゴンボール")
        assertThat(TextNormalizer.normalize("東京タワー")).isEqualTo("東京タワー")
        assertThat(TextNormalizer.normalize("한국어")).isEqualTo("한국어")
    }

    @Test
    fun `drops apostrophes so that both spellings meet`() {
        assertThat(TextNormalizer.normalize("Don't Stop")).isEqualTo("dont stop")
        assertThat(TextNormalizer.normalize("Don’t Stop")).isEqualTo("dont stop")
    }

    @Test
    fun `turns punctuation into single spaces and trims`() {
        assertThat(TextNormalizer.normalize("  Hello,   World! (Live)  ")).isEqualTo("hello world live")
        assertThat(TextNormalizer.normalize("A & B, C")).isEqualTo("a b c")
    }

    @Test
    fun `returns an empty string for blank or punctuation-only input`() {
        assertThat(TextNormalizer.normalize("   ")).isEmpty()
        assertThat(TextNormalizer.normalize("--- !!! ---")).isEmpty()
    }
}
