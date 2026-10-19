package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.score.TrackHint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextQueryBuilderTest {

    private val hint = TrackHint(title = "Paper Lanterns", artist = "The Quiet Harbors", durationMs = 214_000)

    @Test
    fun `escapes every Lucene special character with a backslash`() {
        assertThat(Lucene.escape("""a+b-c!d(e)f:g^h[i]j"k{l}m~n*o?p|q&r/s\t;u"""))
            .isEqualTo("""a\+b\-c\!d\(e\)f\:g\^h\[i\]j\"k\{l\}m\~n\*o\?p\|q\&r\/s\\t\;u""")
    }

    @Test
    fun `leaves ordinary text alone`() {
        assertThat(Lucene.escape("Sigur Rós 22")).isEqualTo("Sigur Rós 22")
    }

    @Test
    fun `terms are lower case and escaped, so title words are never operators`() {
        assertThat(Lucene.terms("Rock AND Roll NOT Dead")).containsExactly("rock", "and", "roll", "not", "dead")
        assertThat(Lucene.terms("AC/DC")).containsExactly("""ac\/dc""")
        assertThat(Lucene.terms("Mr. Brightside (Live)")).containsExactly("mr.", "brightside", """\(live\)""")
    }

    @Test
    fun `terms without a letter or digit are dropped`() {
        assertThat(Lucene.terms("blue - slowed down !!!")).containsExactly("blue", "slowed", "down")
        assertThat(Lucene.terms("  ")).isEmpty()
    }

    @Test
    fun `builds the four variants in order`() {
        val queries = TextQueryBuilder.build(hint)

        assertThat(queries).extracting<QueryVariant> { it.variant }.containsExactly(
            QueryVariant.TITLE_ARTIST_DURATION,
            QueryVariant.TITLE_ARTIST,
            QueryVariant.FUZZY_TITLE_ARTIST_DURATION,
            QueryVariant.TITLE_DURATION,
        )
        // By default every word of the song's name must match (chosen on the M0 corpus run).
        assertThat(queries.map { it.query }).containsExactly(
            "recording:(+paper +lanterns) AND artist:(the quiet harbors) AND dur:[209000 TO 219000] AND video:false",
            "recording:(+paper +lanterns) AND artist:(the quiet harbors) AND video:false",
            "recording:(+paper~1 +lanterns~1) AND artist:(the quiet~1 harbors~1) AND dur:[209000 TO 219000] " +
                "AND video:false",
            "recording:(+paper +lanterns) AND dur:[209000 TO 219000] AND video:false",
        )
    }

    @Test
    fun `with title words optional the variants use the server's OR`() {
        val queries = TextQueryBuilder.build(hint, options = QueryOptions(requireTitleWords = false))
        assertThat(queries.map { it.query }).containsExactly(
            "recording:(paper lanterns) AND artist:(the quiet harbors) AND dur:[209000 TO 219000] AND video:false",
            "recording:(paper lanterns) AND artist:(the quiet harbors) AND video:false",
            "recording:(paper~1 lanterns~1) AND artist:(the quiet~1 harbors~1) AND dur:[209000 TO 219000] " +
                "AND video:false",
            "recording:(paper lanterns) AND dur:[209000 TO 219000] AND video:false",
        )
    }

    @Test
    fun `fuzzy terms are normalized and only words of five or more letters get a tilde`() {
        val fuzzy = TextQueryBuilder.build(hint.copy(title = "Ёлка Beyoncé Rós", artist = "Мой край"))
            .single { it.variant == QueryVariant.FUZZY_TITLE_ARTIST_DURATION }
        assertThat(fuzzy.query).startsWith("recording:(+елка +beyonce~1 +ros) AND artist:(мой край)")
    }

    @Test
    fun `automatic queries ask for 25 results, manual ones for 50`() {
        assertThat(TextQueryBuilder.build(hint)).allSatisfy { assertThat(it.limit).isEqualTo(25) }
        assertThat(TextQueryBuilder.build(hint, TextQueryBuilder.MANUAL_LIMIT))
            .allSatisfy { assertThat(it.limit).isEqualTo(50) }
    }

    @Test
    fun `without a duration only the variant without one remains`() {
        assertThat(TextQueryBuilder.build(hint.copy(durationMs = null)))
            .extracting<QueryVariant> { it.variant }.containsExactly(QueryVariant.TITLE_ARTIST)
    }

    @Test
    fun `without an artist only the title and duration variant remains`() {
        assertThat(TextQueryBuilder.build(hint.copy(artist = null)))
            .extracting<QueryVariant> { it.variant }.containsExactly(QueryVariant.TITLE_DURATION)
        assertThat(TextQueryBuilder.build(hint.copy(artist = " - ")))
            .extracting<QueryVariant> { it.variant }.containsExactly(QueryVariant.TITLE_DURATION)
    }

    @Test
    fun `without a usable title there is nothing to search`() {
        assertThat(TextQueryBuilder.build(hint.copy(title = null))).isEmpty()
        assertThat(TextQueryBuilder.build(hint.copy(title = "???"))).isEmpty()
    }

    @Test
    fun `with required title words every word of the name must match, bracketed words stay optional`() {
        val queries = TextQueryBuilder.build(
            hint.copy(title = "Night Work (Original Mix)"),
            options = QueryOptions(requireTitleWords = true),
        )
        assertThat(queries.first().query).startsWith("recording:(+night +work original mix) AND artist:(")
        assertThat(queries.single { it.variant == QueryVariant.FUZZY_TITLE_ARTIST_DURATION }.query)
            .startsWith("recording:(+night~1 +work original~1 mix) AND artist:(")
    }

    @Test
    fun `required fuzzy words keep their tilde`() {
        val fuzzy = TextQueryBuilder.build(hint, options = QueryOptions(requireTitleWords = true))
            .single { it.variant == QueryVariant.FUZZY_TITLE_ARTIST_DURATION }
        assertThat(fuzzy.query).startsWith("recording:(+paper~1 +lanterns~1) AND artist:(the quiet~1 harbors~1)")
    }

    @Test
    fun `a featured artist and a version after a dash stay optional when title words are required`() {
        val required = QueryOptions(requireTitleWords = true)
        assertThat(TextQueryBuilder.build(hint.copy(title = "Paper Lanterns feat. Ada Vale"), options = required)
            .first().query).startsWith("recording:(+paper +lanterns feat. ada vale) AND")
        assertThat(TextQueryBuilder.build(hint.copy(title = "Paper Lanterns - Live"), options = required)
            .first().query).startsWith("recording:(+paper +lanterns live) AND")
    }

    @Test
    fun `a no-break space before feat works like a space`() {
        val nbsp = Char(0x00A0)
        val query = TextQueryBuilder.build(
            hint.copy(title = "Paper Lanterns${nbsp}feat. Ada Vale"),
            options = QueryOptions(requireTitleWords = true),
        ).first().query
        assertThat(query).startsWith("recording:(+paper +lanterns feat. ada vale) AND")
    }

    @Test
    fun `stylized letters outside the basic plane are searched as plain letters`() {
        val bold = "𝐁𝐥𝐮𝐞" // Mathematical bold "Blue"
        assertThat(TextQueryBuilder.build(hint.copy(title = bold)).first().query).startsWith("recording:(+blue) AND")
    }

    @Test
    fun `a fuzzy variant identical to the first one is not sent again`() {
        val queries = TextQueryBuilder.build(TrackHint(title = "One", artist = "U2", durationMs = 276_000))
        assertThat(queries).extracting<QueryVariant> { it.variant }.containsExactly(
            QueryVariant.TITLE_ARTIST_DURATION,
            QueryVariant.TITLE_ARTIST,
            QueryVariant.TITLE_DURATION,
        )
    }

    @Test
    fun `words in kana, Han or Hangul get no tilde`() {
        val queries = TextQueryBuilder.build(hint.copy(title = "東京タワー", artist = "ドラゴンボール"))
        assertThat(queries.map { it.query }).noneMatch { it.contains("~") }
    }

    @Test
    fun `with title words optional the whole title is sent as escaped words`() {
        val options = QueryOptions(requireTitleWords = false)
        assertThat(TextQueryBuilder.build(hint.copy(title = "Night Work (Original Mix)"), options = options).first().query)
            .startsWith("""recording:(night work \(original mix\)) AND""")
    }

    @Test
    fun `the duration window never goes below zero`() {
        val query = TextQueryBuilder.build(hint.copy(durationMs = 3_000)).first().query
        assertThat(query).contains("dur:[0 TO 8000]")
    }
}
