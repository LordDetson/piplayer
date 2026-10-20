package io.github.lorddetson.piplayer.library.songs

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore

/**
 * The music of the platform's media index on every attached external volume (docs/playback.md §2.1). The index's IDs
 * are unique across volumes, so one content URI form serves them all.
 */
class MediaStoreSongSource(private val context: Context) : SongSource {

    override fun songs(): List<Song> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        // No ORDER BY: the provider would sort again for every cursor window; the list is sorted in Kotlin instead.
        val selection = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.IS_MUSIC} != 0")
        }
        val songs = LinkedHashMap<Long, Song>()
        context.contentResolver.query(collection, projection, selection, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val songId = cursor.getLong(id)
                // A scan between two cursor windows can shift rows, so the same row may come twice.
                songs[songId] = Song(
                    id = songId,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId).toString(),
                    title = cursor.getString(title).orEmpty(),
                    artist = cursor.getString(artist)?.takeUnless { it == MediaStore.UNKNOWN_STRING || it.isBlank() },
                    album = cursor.getString(album)?.takeUnless { it.isBlank() },
                    durationMs = if (cursor.isNull(duration)) null else cursor.getLong(duration).takeIf { it > 0 },
                )
            }
        }
        return songs.values.toList()
    }
}
