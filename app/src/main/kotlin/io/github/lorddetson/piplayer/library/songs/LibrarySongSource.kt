package io.github.lorddetson.piplayer.library.songs

import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.settings.HiddenFiles

/**
 * The songs of a list of the library database, in list order (docs/library.md §1.4, §6.3). The index's IDs are unique
 * across volumes, so one content URI form serves them all.
 */
class LibrarySongSource(
    database: LibraryDatabase,
    hidden: HiddenFiles = HiddenFiles(),
    private val query: SongQuery = SongQuery.SONGS,
) : SongSource {

    private val lists = LibraryLists(database, hidden)

    override fun songs(): List<Song> = lists.songs(query)

    /** The first [count] songs of the list, which a screen can show before the whole list is read. */
    fun firstSongs(count: Int): List<Song> = lists.songs(query, count)
}
