package io.github.lorddetson.piplayer.library.songs

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The media index source on files the test puts into the index itself: an app needs no permission for its own files,
 * and the index reads their tags when they are published (docs/playback.md §2.1). Other rows the device may have are
 * left out of the assertions. Files of a run that died are removed by the next one, but only while the app stays
 * installed: an uninstall leaves them in shared storage without an owner, and the app can no longer delete them.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSongSourceTest {

    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()

    private lateinit var tagged: Uri
    private lateinit var untagged: Uri
    private lateinit var ringtone: Uri

    @Before
    fun insertFixtures() {
        deleteLeftovers()
        tagged = insert("playback/tagged-44100-stereo.mp3", MUSIC, "tagged.mp3")
        untagged = insert("decoding/mp3-44100-stereo.mp3", MUSIC, "Без тегов.mp3")
        ringtone = insert("decoding/mp3-44100-stereo.mp3", RINGTONES, "ring.mp3")
    }

    /** Files of an earlier run that died before its clean-up; the app may delete its own files. */
    private fun deleteLeftovers() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(MUSIC, RINGTONES, context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
            }
        }
    }

    @After
    fun deleteFixtures() {
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    private fun ours() = MediaStoreSongSource(context).songs()
        .filter { song -> inserted.any { it.lastPathSegment == song.id.toString() } }
        .associateBy { it.id }

    private val Uri.id get() = lastPathSegment!!.toLong()

    @Test
    fun musicIsListedWithItsTagsAndLength() {
        val song = ours().getValue(tagged.id)

        assertThat(song.title).isEqualTo("Ёжик в тумане")
        assertThat(song.artist).isEqualTo("PiPlayer Fixtures")
        assertThat(song.album).isEqualTo("Тесты")
        assertThat(song.durationMs).isBetween(1_900L, 2_100L)
    }

    @Test
    fun aFileWithoutTagsIsListedUnderItsName() {
        val song = ours().getValue(untagged.id)

        assertThat(song.title).isEqualTo("Без тегов")
        assertThat(song.artist).isNull()
    }

    @Test
    fun ringtonesAreNotMusic() {
        assertThat(ours().keys).containsExactlyInAnyOrder(tagged.id, untagged.id)
    }

    @Test
    fun theSongsUriPlays() {
        val song = ours().getValue(tagged.id)
        val player = onMain {
            ExoPlayer.Builder(context).build().also {
                it.setMediaItem(MediaItem.fromUri(song.uri))
                it.prepare()
            }
        }
        try {
            player.waitUntil("the song is ready") { playerError == null && playbackState == Player.STATE_READY }
        } finally {
            onMain { player.release() }
        }
    }

    private companion object {
        const val MUSIC = "Music/PiPlayerTest/"
        const val RINGTONES = "Ringtones/PiPlayerTest/"
    }

    /** Puts a test asset into the media index as the app's own file; publishing it runs the index's scan. */
    private fun insert(asset: String, relativePath: String, name: String): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Could not insert $name" }
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out -> TestFiles.assets.open(asset).use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}
