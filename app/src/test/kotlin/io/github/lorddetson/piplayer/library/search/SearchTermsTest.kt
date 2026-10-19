package io.github.lorddetson.piplayer.library.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchTermsTest {

    @Test
    fun `a word is normalized like the stored text`() {
        assertThat(SearchTerms.of("Ёжик")).containsExactly("ежик")
        assertThat(SearchTerms.of("Beyoncé")).containsExactly("beyonce")
    }

    @Test
    fun `several words, longest first, without repeats`() {
        assertThat(SearchTerms.of("live  Beyoncé live")).containsExactly("beyonce", "live")
    }

    @Test
    fun `words of equal length keep the order they were typed in`() {
        assertThat(SearchTerms.of("тум ежи")).containsExactly("тум", "ежи")
    }

    @Test
    fun `SQL wildcards never reach the query`() {
        assertThat(SearchTerms.of("50%_off")).containsExactly("off", "50")
    }

    @Test
    fun `a query without letters or digits has no words`() {
        assertThat(SearchTerms.of("")).isEmpty()
        assertThat(SearchTerms.of("  - / %")).isEmpty()
    }

    @Test
    fun `at most eight words are kept`() {
        val terms = SearchTerms.of("a bb ccc dddd eeeee ffffff ggggggg hhhhhhhh iiiiiiiii jjjjjjjjjj")
        assertThat(terms).hasSize(SearchTerms.MAX_TERMS)
        assertThat(terms.first()).isEqualTo("jjjjjjjjjj")
        assertThat(terms).doesNotContain("a", "bb")
    }
}

class SearchTextTest {

    @Test
    fun `fields are normalized and joined by spaces`() {
        assertThat(SearchText.of("Ёжик в тумане", "Beyoncé", null, "Lo-Fi")).isEqualTo("ежик в тумане beyonce lo fi")
    }

    @Test
    fun `missing and empty fields add nothing`() {
        assertThat(SearchText.of(null, "", "  ", "Title")).isEqualTo("title")
    }

    @Test
    fun `a word cannot be found across two fields`() {
        val text = SearchText.of("Paper", "Lanterns")
        assertThat(text).isEqualTo("paper lanterns")
        assertThat(text.contains("perlan")).isFalse()
    }
}
