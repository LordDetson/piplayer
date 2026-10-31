package io.github.lorddetson.piplayer.library.sync

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/** The platform's media index as the sync reads it (docs/library.md §1.3); tests give their own. */
interface MediaIndex {

    /** The attached external volumes. */
    fun volumes(): Set<String>

    /** The index version of [volume], or null when it cannot be read at the moment. */
    fun version(volume: String): String?

    /** The latest generation of [volume]. */
    fun generation(volume: String): Long

    /** The audio rows of [volume], or only those changed after generation [since]. */
    fun rows(volume: String, since: Long?): List<IndexRow>

    /** The IDs of all audio rows of [volume]. */
    fun ids(volume: String): Set<Long>
}

/** The media index of the device: `MediaStore.Audio.Media` per volume. */
class MediaStoreIndex(private val context: Context) : MediaIndex {

    private val resolver = context.contentResolver

    override fun volumes(): Set<String> = MediaStore.getExternalVolumeNames(context)

    override fun version(volume: String): String? = try {
        MediaStore.getVersion(context, volume)
    } catch (e: IllegalArgumentException) {
        // The volume went away between listing it and asking for its version.
        null
    }

    override fun generation(volume: String): Long = MediaStore.getGeneration(context, volume)

    override fun rows(volume: String, since: Long?): List<IndexRow> {
        val selection = Bundle()
        if (since != null) {
            selection.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?")
            selection.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
        }
        val rows = ArrayList<IndexRow>()
        // No ORDER BY: the provider would sort again for every cursor window.
        resolver.query(MediaStore.Audio.Media.getContentUri(volume), PROJECTION, selection, null)?.use { cursor ->
            val columns = Columns(cursor)
            while (cursor.moveToNext()) rows += columns.row(volume)
        }
        return rows
    }

    override fun ids(volume: String): Set<Long> {
        val ids = HashSet<Long>()
        resolver.query(MediaStore.Audio.Media.getContentUri(volume), arrayOf(MediaStore.Audio.Media._ID), null, null)
            ?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        return ids
    }

    private class Columns(private val cursor: Cursor) {
        private fun index(name: String) = cursor.getColumnIndexOrThrow(name)
        private val id = index(MediaStore.Audio.Media._ID)
        private val relativePath = index(MediaStore.MediaColumns.RELATIVE_PATH)
        private val displayName = index(MediaStore.MediaColumns.DISPLAY_NAME)
        private val size = index(MediaStore.MediaColumns.SIZE)
        private val modified = index(MediaStore.MediaColumns.DATE_MODIFIED)
        private val generation = index(MediaStore.MediaColumns.GENERATION_MODIFIED)
        private val mimeType = index(MediaStore.MediaColumns.MIME_TYPE)
        private val title = index(MediaStore.Audio.AudioColumns.TITLE)
        private val artist = index(MediaStore.Audio.AudioColumns.ARTIST)
        private val album = index(MediaStore.Audio.AudioColumns.ALBUM)
        private val albumArtist = index(MediaStore.Audio.AudioColumns.ALBUM_ARTIST)
        private val composer = index(MediaStore.Audio.AudioColumns.COMPOSER)
        private val genre = index(MediaStore.Audio.AudioColumns.GENRE)
        private val year = index(MediaStore.Audio.AudioColumns.YEAR)
        private val track = index(MediaStore.Audio.AudioColumns.TRACK)
        private val duration = index(MediaStore.Audio.AudioColumns.DURATION)
        private val music = index(MediaStore.Audio.AudioColumns.IS_MUSIC)
        private val podcast = index(MediaStore.Audio.AudioColumns.IS_PODCAST)
        private val audiobook = index(MediaStore.Audio.AudioColumns.IS_AUDIOBOOK)
        private val ringtone = index(MediaStore.Audio.AudioColumns.IS_RINGTONE)
        private val notification = index(MediaStore.Audio.AudioColumns.IS_NOTIFICATION)
        private val alarm = index(MediaStore.Audio.AudioColumns.IS_ALARM)
        private val recording = cursor.getColumnIndex(RECORDING)

        fun row(volume: String) = IndexRow(
            mediaStoreId = cursor.getLong(id),
            volume = volume,
            relativePath = cursor.getString(relativePath).orEmpty(),
            displayName = cursor.getString(displayName).orEmpty(),
            size = cursor.getLong(size),
            modifiedSeconds = cursor.getLong(modified),
            generationModified = cursor.getLong(generation),
            mimeType = cursor.getString(mimeType),
            kinds = MediaKinds(
                music = cursor.flag(music),
                podcast = cursor.flag(podcast),
                audiobook = cursor.flag(audiobook),
                ringtone = cursor.flag(ringtone),
                notification = cursor.flag(notification),
                alarm = cursor.flag(alarm),
                recording = if (recording < 0) null else cursor.flag(recording),
            ),
            title = cursor.getString(title),
            artist = cursor.getString(artist),
            album = cursor.getString(album),
            albumArtist = cursor.getString(albumArtist),
            composer = cursor.getString(composer),
            genre = cursor.getString(genre),
            year = cursor.intOrNull(year),
            trackNumber = cursor.intOrNull(track),
            durationMs = if (cursor.isNull(duration)) null else cursor.getLong(duration),
        )

        private fun Cursor.flag(column: Int) = !isNull(column) && getInt(column) != 0

        private fun Cursor.intOrNull(column: Int) = if (isNull(column)) null else getInt(column)
    }

    private companion object {
        /** `IS_RECORDING` exists from Android 12; Android 11 has no such flag, so the projection asks for it from 12 on. */
        @SuppressLint("InlinedApi")
        val RECORDING: String = MediaStore.Audio.AudioColumns.IS_RECORDING

        val PROJECTION: Array<String> = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.ALBUM_ARTIST,
            MediaStore.Audio.AudioColumns.COMPOSER,
            MediaStore.Audio.AudioColumns.GENRE,
            MediaStore.Audio.AudioColumns.YEAR,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.AudioColumns.IS_MUSIC,
            MediaStore.Audio.AudioColumns.IS_PODCAST,
            MediaStore.Audio.AudioColumns.IS_AUDIOBOOK,
            MediaStore.Audio.AudioColumns.IS_RINGTONE,
            MediaStore.Audio.AudioColumns.IS_NOTIFICATION,
            MediaStore.Audio.AudioColumns.IS_ALARM,
            RECORDING.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        ).toTypedArray()
    }
}
