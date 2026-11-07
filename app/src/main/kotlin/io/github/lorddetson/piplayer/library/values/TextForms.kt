package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.lists.SortKeys
import io.github.lorddetson.piplayer.library.search.SearchText

/** A text with its normalized form; its search field and sort key are derived from that once, when asked for. */
class TextForm internal constructor(val text: String, val normalized: String) {

    private var indexedForm: String? = null
    private var sortKeyForm: String? = null

    /** The text as one field of a search text, with its glued forms (TI §10.1). */
    val indexed: String get() = indexedForm ?: SearchText.indexed(text, normalized).also { indexedForm = it }

    /** The sort key of the text (docs/library.md §6.3). */
    val sortKey: String get() = sortKeyForm ?: SortKeys.of(text, normalized).also { sortKeyForm = it }
}

/**
 * The normalized forms of the texts of a batch of display values (docs/library.md §6.2). A first sync resolves every
 * file of the library: an album, its artist and album artist repeat over its tracks and over the fields of one track, so
 * those are kept for the batch ([shared]), while a title or file name, which only its file has, is normalized once for
 * that file ([once]) and not kept. Not thread-safe: one per batch.
 */
class TextForms(private val normalizer: (String) -> String = TextNormalizer::normalize) {

    private val kept = HashMap<String, TextForm>()

    /** The forms of a text that other files of the batch may share, such as an album or an artist. */
    fun shared(text: String): TextForm = kept.getOrPut(text) { TextForm(text, normalizer(text)) }

    /** The forms of a text of this file alone, such as its title. */
    fun once(text: String): TextForm = TextForm(text, normalizer(text))

    companion object {
        /** The search text of the given fields, missing ones left out, as `SearchText.of` gives it. */
        fun searchText(vararg fields: TextForm?): String =
            fields.mapNotNull { field -> field?.indexed?.takeIf { it.isNotEmpty() } }.joinToString(" ")
    }
}
