package io.github.lorddetson.piplayer.library.sync

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.songs.LibrarySongSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device's media index as the sync reads it, on files the test puts into the index itself: an app needs no
 * permission for its own files, and the index reads their tags when they are published (docs/library.md §1.3). Other
 * rows the device may have are left out of the assertions. Files of a run that died are removed by the next one, but
 * only while the app stays installed: an uninstall leaves them in shared storage without an owner, and the app can no
 * longer delete them.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreIndexTest {

    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver
    private val index = MediaStoreIndex(context)
    private val primary = MediaStore.VOLUME_EXTERNAL_PRIMARY
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

    private val Uri.id get() = lastPathSegment!!.toLong()

    private fun ours(since: Long? = null) = index.rows(primary, since)
        .filter { row -> inserted.any { it.id == row.mediaStoreId } }
        .associateBy { it.mediaStoreId }

    @Test
    fun theVolumesAreTheAttachedExternalOnesWithAVersionAndAGeneration() {
        assertThat(index.volumes()).contains(primary)
        assertThat(index.version(primary)).isNotBlank()
        assertThat(index.generation(primary)).isGreaterThanOrEqualTo(ours().values.maxOf { it.generationModified })
    }

    @Test
    fun aRowCarriesTheFileTheTagsAndTheKinds() {
        val row = ours().getValue(tagged.id)

        assertThat(row.volume).isEqualTo(primary)
        assertThat(row.relativePath).isEqualTo(MUSIC)
        assertThat(row.displayName).isEqualTo("tagged.mp3")
        assertThat(row.size).isEqualTo(TestFiles.assets.open("playback/tagged-44100-stereo.mp3").use { it.readBytes().size.toLong() })
        assertThat(row.modifiedSeconds).isPositive()
        assertThat(row.mimeType).isEqualTo("audio/mpeg")
        assertThat(row.title).isEqualTo("Ёжик в тумане")
        assertThat(row.artist).isEqualTo("PiPlayer Fixtures")
        assertThat(row.album).isEqualTo("Тесты")
        assertThat(row.durationMs).isBetween(1_900L, 2_100L)
        assertThat(row.kinds.music).isTrue()
        assertThat(row.kinds.ringtone).isFalse()
        assertThat(row.kinds.recording).isEqualTo(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) false else null)
    }

    @Test
    fun aRingtoneIsReadButIsNotMusic() {
        val row = ours().getValue(ringtone.id)

        assertThat(row.kinds.music).isFalse()
        assertThat(row.kinds.ringtone).isTrue()
    }

    @Test
    fun onlyRowsChangedAfterAGenerationAreReadAgain() {
        val generation = index.generation(primary)

        resolver.update(untagged, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "Переименован.mp3") }, null, null)

        val changed = ours(since = generation)
        assertThat(changed.keys).containsExactly(untagged.id)
        assertThat(changed.getValue(untagged.id).displayName).isEqualTo("Переименован.mp3")
        assertThat(changed.getValue(untagged.id).generationModified).isGreaterThan(generation)
    }

    @Test
    fun theIdOfADeletedRowIsGone() {
        assertThat(index.ids(primary)).contains(tagged.id, untagged.id, ringtone.id)

        resolver.delete(tagged, null, null)

        assertThat(index.ids(primary)).doesNotContain(tagged.id).contains(untagged.id, ringtone.id)
    }

    @Test
    fun theLibraryListsTheMusicOfTheIndexAndItsUrisPlay() = runBlocking<Unit> {
        val database = LibraryDatabase.open(context, file = null)
        val sync = LibrarySync(database, index, mayRead = { true })
        try {
            sync.sync()
            val songs = LibrarySongSource(database).songs()
                .filter { song -> inserted.any { it.id == song.id } }
                .associateBy { it.id }

            assertThat(songs.keys).containsExactlyInAnyOrder(tagged.id, untagged.id)
            assertThat(songs.getValue(untagged.id).title).isEqualTo("Без тегов")
            assertThat(songs.getValue(untagged.id).artist).isNull()
            val song = songs.getValue(tagged.id)
            assertThat(song.uri).isEqualTo("content://media/external/audio/media/${tagged.id}")
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
        } finally {
            sync.close()
            database.close()
        }
    }

    private companion object {
        const val MUSIC = "Music/PiPlayerTest/"
        const val RINGTONES = "Ringtones/PiPlayerTest/"
    }

    /** Puts a test asset into the media index as the app's own file; publishing it runs the index's scan. */
    private fun insert(asset: String, relativePath: String, name: String): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(primary)
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
