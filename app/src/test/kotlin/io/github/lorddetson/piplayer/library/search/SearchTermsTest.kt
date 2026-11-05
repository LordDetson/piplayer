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
        assertThat(SearchText.of("Ёжик в тумане", "Beyoncé", null, "Lo-Fi")).isEqualTo("ежик в тумане beyonce lo fi lofi")
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

    @Test
    fun `a word with punctuation inside is also kept without it`() {
        assertThat(SearchText.of("AC/DC")).isEqualTo("ac dc acdc")
        assertThat(SearchText.of("blink-182")).isEqualTo("blink 182 blink182")
        assertThat(SearchText.of("Y.M.C.A.")).isEqualTo("y m c a ymca")
    }

    @Test
    fun `punctuation at the edges of a word and apostrophes add nothing`() {
        assertThat(SearchText.of("(Live)")).isEqualTo("live")
        assertThat(SearchText.of("Don't Stop!")).isEqualTo("dont stop")
    }

    @Test
    fun `an underscore separates words, so a whole file name is not glued`() {
        assertThat(SearchText.of("Artist_Name_Title")).isEqualTo("artist name title")
        assertThat(SearchText.of("Artist_Name-Title")).isEqualTo("artist name title nametitle")
    }

    @Test
    fun `neighbouring words of up to two letters are also kept together`() {
        assertThat(SearchText.of("AC DC")).isEqualTo("ac dc acdc")
        assertThat(SearchText.of("AC / DC")).isEqualTo("ac dc acdc")
        assertThat(SearchText.of("R E M")).isEqualTo("r e m rem")
        assertThat(SearchText.of("Би 2")).isEqualTo("би 2 би2")
        assertThat(SearchText.of("10 CC")).isEqualTo("10 cc 10cc")
    }

    @Test
    fun `a run of short words stops at a longer word`() {
        assertThat(SearchText.of("Take On Me")).isEqualTo("take on me onme")
        assertThat(SearchText.of("On Me Now")).isEqualTo("on me now onme")
        assertThat(SearchText.of("a-ha Take On Me")).isEqualTo("a ha take on me aha onme")
    }

    @Test
    fun `words of three letters or more, and single short words, are not glued`() {
        assertThat(SearchText.of("The End")).isEqualTo("the end")
        assertThat(SearchText.of("Blink 182")).isEqualTo("blink 182")
        assertThat(SearchText.of("Ёжик в тумане")).isEqualTo("ежик в тумане")
    }

    @Test
    fun `length counts letters, so a letter outside the basic plane is one letter`() {
        assertThat(SearchText.of("𠮷𠮷 𠮷")).isEqualTo("𠮷𠮷 𠮷 𠮷𠮷𠮷")
    }

    @Test
    fun `short words of two fields are never glued together`() {
        assertThat(SearchText.of("AC", "DC")).isEqualTo("ac dc")
        assertThat(SearchText.of("AC/", "/DC")).isEqualTo("ac dc")
    }

    @Test
    fun `every kind of space separates words`() {
        assertThat(SearchText.of("Night Lo-Fi")).isEqualTo("night lo fi lofi")
    }
}

/** A query against stored text the way the search column answers it: every word found anywhere in the text. */
class SearchMatchTest {

    private fun finds(query: String, vararg fields: String?): Boolean {
        val text = SearchText.of(*fields)
        return SearchTerms.of(query).let { terms -> terms.isNotEmpty() && terms.all { it in text } }
    }

    @Test
    fun `ё and diacritics keep meeting their plain spelling`() {
        assertThat(finds("ежик", "Ёжик в тумане")).isTrue()
        assertThat(finds("Ёжик", "Ежик в тумане")).isTrue()
        assertThat(finds("Beyonce", "Beyoncé")).isTrue()
        assertThat(finds("Beyoncé", "Beyonce")).isTrue()
    }

    @Test
    fun `letters without a decomposition meet their plain spelling`() {
        assertThat(finds("mo", "MØ")).isTrue()
        assertThat(finds("trentemoller", "Trentemøller")).isTrue()
        assertThat(finds("Trentemøller", "Trentemoller")).isTrue()
        assertThat(finds("lodz", "Łódź")).isTrue()
        assertThat(finds("dorde", "Đorđe")).isTrue()
        assertThat(finds("aeon", "Æon")).isTrue()
        assertThat(finds("oeuvre", "Œuvre")).isTrue()
        assertThat(finds("strasse", "Straße")).isTrue()
        assertThat(finds("kirac", "Kıraç")).isTrue()
        assertThat(finds("med", "Með")).isTrue()
        assertThat(finds("thor", "Þór")).isTrue()
    }

    @Test
    fun `halfwidth and full kana meet`() {
        assertThat(finds("ドラゴン", "ﾄﾞﾗｺﾞﾝﾎﾞｰﾙ")).isTrue()
        assertThat(finds("ﾄﾞﾗｺﾞﾝ", "ドラゴンボール")).isTrue()
    }

    @Test
    fun `a name is found with or without its punctuation`() {
        assertThat(finds("acdc", "AC/DC")).isTrue()
        assertThat(finds("ac dc", "AC/DC")).isTrue()
        assertThat(finds("AC/DC", "AC/DC")).isTrue()
        assertThat(finds("AC/DC", "ACDC")).isTrue()
        assertThat(finds("acdc", "AC DC")).isTrue()
        assertThat(finds("acdc", "AC-DC - Thunderstruck")).isTrue()
        assertThat(finds("rem", "R.E.M.")).isTrue()
        assertThat(finds("ymca", "Y.M.C.A.")).isTrue()
        assertThat(finds("aha", "a-ha")).isTrue()
        assertThat(finds("blink182", "blink-182")).isTrue()
        assertThat(finds("би2", "Би-2")).isTrue()
        assertThat(finds("тиктак", "Тик-так")).isTrue()
    }

    @Test
    fun `a glued word is found only inside one field`() {
        assertThat(finds("acdc", "AC", "DC")).isFalse()
    }

    @Test
    fun `longer words written apart are not found glued`() {
        assertThat(finds("blink182", "Blink 182")).isFalse()
        assertThat(finds("theend", "The End")).isFalse()
    }
}
