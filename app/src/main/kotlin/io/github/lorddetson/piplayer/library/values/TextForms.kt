package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import io.github.lorddetson.piplayer.library.search.SearchText
import io.github.lorddetson.piplayer.library.sort.SortKey
import io.github.lorddetson.piplayer.library.sort.SortKeys

/** A text with its normalized form; its search field and sort key are derived once, when asked for. */
class TextForm internal constructor(val text: String, val normalized: String, private val forms: TextForms) {

    private var indexedForm: String? = null

    /** The text as one field of a search text, with its glued forms (TI §10.1). */
    val indexed: String get() = indexedForm ?: SearchText.indexed(text, normalized).also { indexedForm = it }

    /** The collator's key of the text (docs/library.md §11.1). */
    val sortKey: SortKey get() = forms.sortKey(text)
}

/**
 * The normalized forms and sort keys of the texts of a batch of display values (docs/library.md §6.2, §11.1); a
 * resolver chunk gives [keys] a collator of its own. A first sync resolves every
 * file of the library: an album, its artist and album artist repeat over its tracks and over the fields of one track, so
 * those are kept for the batch ([shared]), while a title or file name, which only its file has, is normalized once for
 * that file ([once]) and not kept. Not thread-safe: one per batch.
 */
class TextForms(
    private val keys: (String) -> SortKey = SortKeys::of,
    private val folders: (String) -> String = AlbumKey::folderKey,
    private val normalizer: (String) -> String = TextNormalizer::normalize,
) {

    private val kept = HashMap<String, TextForm>()
    private val sortKeys = HashMap<String, SortKey>()
    private val albumFolders = HashMap<String, String>()

    /** The forms of a text that other files of the batch may share, such as an album or an artist. */
    fun shared(text: String): TextForm = kept.getOrPut(text) { TextForm(text, normalizer(text), this) }

    /** The forms of a text of this file alone, such as its title. */
    fun once(text: String): TextForm = TextForm(text, normalizer(text), this)

    /**
     * The collator's key of [text], made once per batch (docs/library.md §11.1); also for a sort tag, which needs no
     * normalized form.
     */
    fun sortKey(text: String): SortKey = sortKeys.getOrPut(text) { keys(text) }

    /**
     * The folder part of the album keys of a folder's files (AlbumKey.folderKey), derived once per folder of the batch:
     * it took a third of resolving a first sync.
     */
    fun albumFolder(relativePath: String): String = albumFolders.getOrPut(relativePath) { folders(relativePath) }

    companion object {
        /** The search text of the given fields, missing ones left out, as `SearchText.of` gives it. */
        fun searchText(vararg fields: TextForm?): String {
            // Every file of a first sync comes here: no lists in between.
            val text = StringBuilder()
            for (field in fields) {
                val indexed = field?.indexed ?: continue
                if (indexed.isEmpty()) continue
                if (text.isNotEmpty()) text.append(' ')
                text.append(indexed)
            }
            return text.toString()
        }
    }
}
