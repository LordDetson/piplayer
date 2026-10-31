package io.github.lorddetson.piplayer.library.songs

import android.content.ContentUris
import android.provider.MediaStore
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.SongRow
import kotlinx.coroutines.runBlocking

/**
 * The music of the library database, in list order (docs/library.md §1.4). The index's IDs are unique across volumes,
 * so one content URI form serves them all.
 */
class LibrarySongSource(private val database: LibraryDatabase) : SongSource {

    override fun songs(): List<Song> = runBlocking { database.library().songs() }.map(::song)

    /** The first [count] songs of the list, which a screen can show before the whole list is read. */
    fun firstSongs(count: Int): List<Song> = runBlocking { database.library().firstSongs(count) }.map(::song)

    private fun song(row: SongRow) = Song(
        id = row.mediaStoreId,
        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.mediaStoreId).toString(),
        title = row.title,
        artist = row.artist,
        album = row.album,
        durationMs = row.durationMs,
    )
}
