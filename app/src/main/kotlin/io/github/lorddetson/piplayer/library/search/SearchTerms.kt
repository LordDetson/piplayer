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

/** The normalized text a track is found by. */
object SearchText {

    /** The given fields (missing ones left out), each normalized, separated by spaces so no word spans two fields. */
    fun of(vararg fields: String?): String = fields
        .mapNotNull { field -> field?.let(TextNormalizer::normalize)?.takeIf { it.isNotEmpty() } }
        .joinToString(" ")
}
