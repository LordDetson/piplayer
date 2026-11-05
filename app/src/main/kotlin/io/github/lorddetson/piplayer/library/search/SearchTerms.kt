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

    /** Two or more neighbouring words of up to two letters in normalized text. */
    private val shortWords = Regex("""(?<!\S)\S{1,2}(?: \S{1,2})+(?!\S)""")

    /** The given fields (missing ones left out), each [indexed], separated by spaces so no word spans two fields. */
    fun of(vararg fields: String?): String = fields
        .mapNotNull { field -> field?.let(::indexed)?.takeIf { it.isNotEmpty() } }
        .joinToString(" ")

    /** The normalized [field], then its glued words: each word with punctuation inside, each run of short words. */
    private fun indexed(field: String): String {
        val normalized = TextNormalizer.normalize(field)
        val splitWords = field.split(wordBreak)
            .filter { word -> word.any { !it.isLetterOrDigit() } }
            .map(TextNormalizer::normalize)
            .filter { ' ' in it }
        val shortRuns = shortWords.findAll(normalized).map { it.value }
        val glued = (splitWords + shortRuns).map { it.replace(" ", "") }.distinct()
        return (listOf(normalized) + glued).joinToString(" ")
    }
}
