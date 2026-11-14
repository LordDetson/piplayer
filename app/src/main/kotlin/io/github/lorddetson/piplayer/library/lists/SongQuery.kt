package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.db.FolderKey

/** The kinds of song lists (docs/library.md §6.4); [token] names them in queue requests. */
enum class ListKind(val token: String, val defaultSort: SongSort) {
    SONGS("songs", SongSort.TITLE),
    ALBUM("album", SongSort.TRACK),
    ARTIST("artist", SongSort.YEAR),
    FOLDER("folder", SongSort.FILE_NAME),

    /** A folder with all its subfolders. */
    TREE("tree", SongSort.FILE_NAME),

    /** A playlist by its ID, in its own order (docs/library.md §16.4). */
    PLAYLIST("playlist", SongSort.POSITION),

    /** The smart lists (docs/library.md §17): the favorite songs. */
    FAVORITES("favorites", SongSort.TITLE),

    /** The newest songs by the date the media index added them, in their own order. */
    RECENTLY_ADDED("added", SongSort.POSITION),

    /** The songs played last, in their own order. */
    RECENTLY_PLAYED("recent", SongSort.POSITION),

    /** The songs played most, in their own order. */
    MOST_PLAYED("most", SongSort.POSITION),

    /** The read songs with one of the chosen reasons of missing information, which the key holds ([MissingReasons]). */
    MISSING_INFO("missing", SongSort.TITLE),
    ;

    /** Whether this is a smart list (docs/library.md §17): read anew for every request, never kept. */
    val smart: Boolean get() = this in SMART

    /** Whether a list of this kind is named by a key: all but the Songs list and the smart lists other than [MISSING_INFO]. */
    val keyed: Boolean get() = this != SONGS && (!smart || this == MISSING_INFO)

    /** Whether this is one of the smart lists of at most [SmartLists.RANKED_LIMIT] songs in their own order. */
    val ranked: Boolean get() = this == RECENTLY_ADDED || this == RECENTLY_PLAYED || this == MOST_PLAYED

    companion object {
        fun of(token: String): ListKind? = entries.firstOrNull { it.token == token }

        /** The smart lists in the order the Playlists tab shows them (docs/library.md §17.2). */
        val SMART: List<ListKind> by lazy { listOf(FAVORITES, RECENTLY_ADDED, RECENTLY_PLAYED, MOST_PLAYED, MISSING_INFO) }
    }
}

/** The orders of song lists (docs/library.md §6.3); [token] names them in queue requests. */
enum class SongSort(val token: String) {
    TITLE("title"),
    ARTIST("artist"),
    ALBUM("album"),
    LENGTH("length"),

    /** Disc, then track number. */
    TRACK("track"),

    /** Album by year, then disc and track. */
    YEAR("year"),
    FILE_NAME("file"),

    /** A list's own order: a playlist's, or that of a smart list of [SmartLists.RANKED_LIMIT] songs. */
    POSITION("position"),
    ;

    companion object {
        fun of(token: String): SongSort? = entries.firstOrNull { it.token == token }
    }
}

/**
 * A song list as the user sees it (docs/library.md §6.4): which songs, in which order, with which search. [key] names the
 * album, artist or folder; the song list has none.
 */
data class SongQuery(
    val kind: ListKind,
    val key: String = "",
    val sort: SongSort = kind.defaultSort,
    val reversed: Boolean = false,
    val search: String = "",
) {
    /** The folder of a folder or tree list. */
    val folder: FolderKey? get() = if (kind == ListKind.FOLDER || kind == ListKind.TREE) FolderKey.parse(key) else null

    /** The playlist of a playlist list. */
    val playlistId: Long? get() = if (kind == ListKind.PLAYLIST) key.toLongOrNull() else null

    /**
     * The chosen reasons of Songs missing information (docs/library.md §17.3), a set of [MissingReasons]; null for another
     * list or a key that is no such set.
     */
    val missingReasons: Int?
        get() = if (kind == ListKind.MISSING_INFO) key.toIntOrNull()?.takeIf { it in 0..MissingReasons.ALL && it.toString() == key } else null

    companion object {
        /** The Songs tab by title, also what a request of version 1 names. */
        val SONGS = SongQuery(ListKind.SONGS)

        fun album(key: String) = SongQuery(ListKind.ALBUM, key)

        fun artist(key: String) = SongQuery(ListKind.ARTIST, key)

        fun folder(volume: String, relativePath: String) = SongQuery(ListKind.FOLDER, FolderKey(volume, relativePath).key)

        fun tree(volume: String, relativePath: String) = SongQuery(ListKind.TREE, FolderKey(volume, relativePath).key)

        fun playlist(id: Long) = SongQuery(ListKind.PLAYLIST, id.toString())

        fun favorites() = SongQuery(ListKind.FAVORITES)

        fun recentlyAdded() = SongQuery(ListKind.RECENTLY_ADDED)

        fun recentlyPlayed() = SongQuery(ListKind.RECENTLY_PLAYED)

        fun mostPlayed() = SongQuery(ListKind.MOST_PLAYED)

        /** Songs missing information with the [reasons] chosen, a set of [MissingReasons]. */
        fun missingInfo(reasons: Int = MissingReasons.DEFAULT) = SongQuery(ListKind.MISSING_INFO, reasons.toString())

        /** The smart list of [kind] without a search; [missing] are the chosen reasons of Songs missing information. */
        fun smart(kind: ListKind, missing: Int = MissingReasons.DEFAULT): SongQuery {
            require(kind.smart) { "$kind is no smart list" }
            return if (kind == ListKind.MISSING_INFO) missingInfo(missing) else SongQuery(kind)
        }
    }
}
