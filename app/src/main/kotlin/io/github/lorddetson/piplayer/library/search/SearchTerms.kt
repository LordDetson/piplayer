package io.github.lorddetson.piplayer.library.search

import io.github.lorddetson.piplayer.identification.text.TextNormalizer

/**
 * The words of a library search (features: search that ignores case, diacritics and the difference between ё and е).
 * The query is normalized like the stored search text, so both sides compare in one form.
 */
object SearchTerms {

    /** More words narrow the result no further in practice, and each one costs a pass over the text. */
    const val MAX_TERMS = 8

    /**
     * The normalized words of [query] without repeats, longest first (a long word rules out most rows early), at most
     * [MAX_TERMS]. Punctuation becomes a word break, so no SQL wildcard reaches the query.
     */
    fun of(query: String): List<String> = TextNormalizer.normalize(query)
        .split(' ')
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .take(MAX_TERMS)
}

/**
 * The normalized text a track is found by. A name is also kept without what separates its parts, so that "acdc" finds
 * both "AC/DC" and "AC DC". Queries are not glued: "AC/DC" reaches the search as two words, which also find "ACDC".
 */
object SearchText {

    /** What separates the words of a field; an underscore counts, so that a whole file name is not one glued word. */
    private val wordBreak = Regex("""[\s\p{Z}_]+""")

    /** The given fields (missing ones left out), each [indexed], separated by spaces so no word spans two fields. */
    fun of(vararg fields: String?): String = fields
        .mapNotNull { field -> field?.let(::indexed)?.takeIf { it.isNotEmpty() } }
        .joinToString(" ")

    /** The normalized [field], then its glued words: each word with punctuation inside, each run of short words. */
    private fun indexed(field: String): String {
        val normalized = TextNormalizer.normalize(field)
        // A first sync brings here every value of the library: most of them have nothing to glue.
        val splitWords = if (field.any { !it.isLetterOrDigit() && !it.isWhitespace() && it != '_' }) {
            field.split(wordBreak)
                .filter { word -> word.any { !it.isLetterOrDigit() } }
                .map(TextNormalizer::normalize)
                .filter { ' ' in it }
        } else {
            emptyList()
        }
        val glued = (splitWords + shortRuns(normalized)).map { it.replace(" ", "") }.distinct()
        if (glued.isEmpty()) return normalized
        return (listOf(normalized) + glued).joinToString(" ")
    }

    /** Two or more neighbouring words of up to two letters in normalized text, whose words are one space apart. */
    private fun shortRuns(normalized: String): List<String> {
        var runs: MutableList<String>? = null
        var runStart = -1
        var runWords = 0
        var wordStart = 0
        var i = 0
        while (i <= normalized.length) {
            if (i < normalized.length && normalized[i] != ' ') {
                i++
                continue
            }
            if (normalized.codePointCount(wordStart, i) in 1..2) {
                if (runStart < 0) runStart = wordStart
                runWords++
            } else {
                if (runWords >= 2) (runs ?: mutableListOf<String>().also { runs = it }) += normalized.substring(runStart, wordStart - 1)
                runStart = -1
                runWords = 0
            }
            wordStart = i + 1
            i++
        }
        if (runWords >= 2) (runs ?: mutableListOf<String>().also { runs = it }) += normalized.substring(runStart)
        return runs.orEmpty()
    }
}
