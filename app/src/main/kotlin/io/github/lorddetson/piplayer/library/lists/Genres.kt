package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.sort.Sections
import io.github.lorddetson.piplayer.library.sort.SortKey
import io.github.lorddetson.piplayer.library.values.TextForms

/** The genre key of a name (docs/library.md §19.1, §19.4). */
object GenreKeys {
    /**
     * The key of the songs without a genre, "Unknown genre", which no name gives (§19.4): a key from a name is normalized
     * text, which holds no punctuation, or a name of punctuation alone, which holds no letter.
     */
    const val NONE = "!none"

    /** The normalized name, as an artist's key is (docs/library.md §6.2); a name of punctuation alone keeps itself. */
    fun of(name: String): String = DisplayArtists.keyOf(name)
}

/** A genre of a file as lists group it: the name, its key, its search text and its sort key (docs/library.md §19.1). */
data class DisplayGenre(val name: String, val key: String, val searchText: String, val sortKey: SortKey = SortKey.EMPTY)

/** The genres of a file from its merged values (docs/library.md §19.1). */
object DisplayGenres {
    /** [names] trimmed, without blank ones, one per key in their order; [forms] are shared by a batch. */
    fun of(names: List<String>, forms: TextForms = TextForms()): List<DisplayGenre> {
        if (names.isEmpty()) return emptyList()
        val genres = ArrayList<DisplayGenre>(names.size)
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val form = forms.shared(name)
            val key = DisplayArtists.keyOf(name, form.normalized)
            if (genres.none { it.key == key }) genres += DisplayGenre(name, key, form.indexed, forms.sortKey(name))
        }
        return genres
    }
}

/** The orders of the Genres tab (docs/library.md §19.2); [SONGS] puts the genre with the most songs first. */
enum class GenreSort { NAME, SONGS }

/**
 * A genre as lists show it; [key] is its genre key, which its page is found by, [GenreKeys.NONE] for "Unknown genre"
 * (docs/library.md §19.2, §19.4), whose [name] is empty.
 */
data class GenreGroup(
    val key: String,
    val name: String,
    val songs: Int,
    /** The section of the scroll thumb's bubble, by the list's order (docs/library.md §11.2). */
    val section: String? = null,
    internal val sortKey: SortKey? = null,
) {
    /** Whether this is the row of the songs without a genre. */
    val unknown: Boolean get() = key == GenreKeys.NONE
}

/** The order of the Genres list (docs/library.md §19.2). */
object GenreOrder {
    /**
     * [genres] in [sort] (reversed for [reversed]) with their sections, then "Unknown genre" with [unknownSongs] songs
     * last unless there are none or [searching]; [letter] gives the section of a name's sort key. Ties go by the name's
     * sort key, then the genre's key, so that the order is total and its reverse exact.
     */
    fun of(
        genres: List<GenreGroup>,
        unknownSongs: Int,
        sort: GenreSort,
        reversed: Boolean,
        searching: Boolean,
        letter: (SortKey?) -> String?,
    ): List<GenreGroup> {
        val byName = compareBy<GenreGroup, SortKey?>(nullsLast()) { it.sortKey }.thenBy { it.key }
        val comparator = when (sort) {
            GenreSort.NAME -> byName
            GenreSort.SONGS -> compareByDescending<GenreGroup> { it.songs }.then(byName)
        }
        val sorted = genres.sortedWith(if (reversed) comparator.reversed() else comparator).map { genre ->
            genre.copy(
                section = when (sort) {
                    GenreSort.NAME -> letter(genre.sortKey)
                    GenreSort.SONGS -> Sections.count(genre.songs)
                },
            )
        }
        if (searching || unknownSongs <= 0) return sorted
        // Always last, so that untagged songs stay reachable; it takes the section of the row before it.
        return sorted + GenreGroup(GenreKeys.NONE, "", unknownSongs, section = sorted.lastOrNull()?.section)
    }
}
