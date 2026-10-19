package io.github.lorddetson.piplayer.identification.text

import io.github.lorddetson.piplayer.identification.score.TrackHint
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** The stage 4 query variants of TI §3.3, in the order they are tried. */
enum class QueryVariant {
    TITLE_ARTIST_DURATION,
    TITLE_ARTIST,
    FUZZY_TITLE_ARTIST_DURATION,
    TITLE_DURATION,
}

/** One MusicBrainz recording search: a Lucene query and the number of results to ask for. */
data class RecordingQuery(val variant: QueryVariant, val query: String, val limit: Int)

/**
 * Tuning switches of the text search, compared on the owner's corpus in M0.
 *
 * @property requireTitleWords every word of the song's name must match (`+word`); the version and a featured artist
 *   stay optional. Off: all words are optional, which is the server's default OR.
 * @property bracketVersion a version written without brackets ("Song Radio Edit") is put into brackets before the
 *   search and the scoring.
 */
data class QueryOptions(val requireTitleWords: Boolean = true, val bracketVersion: Boolean = true)

/** Lucene query syntax as the MusicBrainz search server reads it. */
object Lucene {

    private const val SPECIAL = "+-!():^[]\"{}~*?|&/;\\"
    private val whitespace = Regex("""\s+""")

    /** Backslash-escapes every character with a meaning in the query syntax. */
    fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            if (char in SPECIAL) append('\\')
            append(char)
        }
    }

    /**
     * Splits text into lower-case escaped terms. Lower case keeps title words such as "AND" or "NOT" from being read
     * as operators; compatibility forms (stylized or full-width letters) become plain letters; terms without a letter
     * or digit are dropped.
     */
    fun terms(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .split(whitespace)
        .filter { term -> term.codePoints().anyMatch(Character::isLetterOrDigit) }
        .map(::escape)
}

/** Builds the text search queries for a file (TI §3.3). */
object TextQueryBuilder {

    const val AUTOMATIC_LIMIT = 25
    const val MANUAL_LIMIT = 50
    const val DURATION_WINDOW_MS = 5_000L

    /** Words this long or longer may differ by one edit in the fuzzy variant. */
    private const val FUZZY_MIN_LENGTH = 5

    /**
     * Scripts written without spaces between words: the server splits them itself, which a fuzzy term would skip,
     * so their words get no tilde.
     */
    private val unspacedScripts = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
    )

    /** A featured artist at the end of a title, which MusicBrainz keeps in the artist credit. */
    private val featuredTail = Regex("""\s+\(?(?:featuring\b|feat\b\.?|ft\b\.?)\s*.*$""", RegexOption.IGNORE_CASE)

    /** Variants that need a missing value are left out, and so is a variant identical to an earlier one. */
    fun build(
        hint: TrackHint,
        limit: Int = AUTOMATIC_LIMIT,
        options: QueryOptions = QueryOptions(),
    ): List<RecordingQuery> {
        val (outside, inside) = hint.title?.let { requiredAndOptional(it, options.bracketVersion) } ?: Pair("", "")
        val required = options.requireTitleWords && Lucene.terms(outside).isNotEmpty()
        val title = if (required) {
            Lucene.terms(outside).map { "+$it" } + Lucene.terms(inside)
        } else {
            hint.title?.let(Lucene::terms).orEmpty()
        }
        if (title.isEmpty()) return emptyList()
        val artist = hint.artist?.let(Lucene::terms).orEmpty()
        val duration = hint.durationMs?.let { "dur:[${max(0, it - DURATION_WINDOW_MS)} TO ${it + DURATION_WINDOW_MS}]" }
        val fuzzyTitle = if (required) {
            fuzzyTerms(outside).map { "+$it" } + fuzzyTerms(inside)
        } else {
            fuzzyTerms(hint.title)
        }
        val fuzzyArtist = fuzzyTerms(hint.artist)

        val recording = clause("recording", title)
        val credit = artist.takeIf { it.isNotEmpty() }?.let { clause("artist", it) }
        return buildList {
            if (credit != null && duration != null) {
                add(QueryVariant.TITLE_ARTIST_DURATION, recording, credit, duration)
            }
            if (credit != null) add(QueryVariant.TITLE_ARTIST, recording, credit)
            if (credit != null && duration != null && fuzzyTitle.isNotEmpty() && fuzzyArtist.isNotEmpty()) {
                add(
                    QueryVariant.FUZZY_TITLE_ARTIST_DURATION,
                    clause("recording", fuzzyTitle),
                    clause("artist", fuzzyArtist),
                    duration,
                )
            }
            if (duration != null) add(QueryVariant.TITLE_DURATION, recording, duration)
        }.distinctBy { (_, query) -> query }.map { (variant, query) -> RecordingQuery(variant, query, limit) }
    }

    /**
     * The words of the song's name, which must all match when [QueryOptions.requireTitleWords] is on, and the words
     * that may: a version description (also one written without brackets) and a featured artist.
     */
    private fun requiredAndOptional(title: String, bracketVersion: Boolean): Pair<String, String> {
        val plain = Spaces.plain(title)
        var (name, version) = Brackets.split(if (bracketVersion) VersionSuffix.bracket(plain) else plain)
        featuredTail.find(name)?.let { tail ->
            version = (tail.value.trim() + " " + version).trim()
            name = name.substring(0, tail.range.first).trim()
        }
        return name to version
    }

    private fun MutableList<Pair<QueryVariant, String>>.add(variant: QueryVariant, vararg clauses: String) {
        add(variant to (clauses.toList() + "video:false").joinToString(" AND "))
    }

    private fun clause(field: String, terms: List<String>): String = "$field:(${terms.joinToString(" ")})"

    /** Normalized words, so no escaping is needed; long words in spaced scripts get a one-edit tilde. */
    private fun fuzzyTerms(text: String?): List<String> = text
        ?.let(TextNormalizer::normalize)
        ?.split(' ')
        ?.filter { it.isNotEmpty() }
        ?.map { word -> if (fuzzy(word)) "$word~1" else word }
        .orEmpty()

    private fun fuzzy(word: String): Boolean =
        word.codePointCount(0, word.length) >= FUZZY_MIN_LENGTH &&
            word.codePoints().noneMatch { Character.UnicodeScript.of(it) in unspacedScripts }
}
