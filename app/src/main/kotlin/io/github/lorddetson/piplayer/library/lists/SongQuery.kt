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
    ;

    companion object {
        fun of(token: String): ListKind? = entries.firstOrNull { it.token == token }
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

    /** A playlist's own order. */
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

    companion object {
        /** The Songs tab by title, also what a request of version 1 names. */
        val SONGS = SongQuery(ListKind.SONGS)

        fun album(key: String) = SongQuery(ListKind.ALBUM, key)

        fun artist(key: String) = SongQuery(ListKind.ARTIST, key)

        fun folder(volume: String, relativePath: String) = SongQuery(ListKind.FOLDER, FolderKey(volume, relativePath).key)

        fun tree(volume: String, relativePath: String) = SongQuery(ListKind.TREE, FolderKey(volume, relativePath).key)

        fun playlist(id: Long) = SongQuery(ListKind.PLAYLIST, id.toString())
    }
}
