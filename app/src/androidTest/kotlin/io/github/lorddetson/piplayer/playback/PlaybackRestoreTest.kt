package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The queue and position survive process death: saving while playing, restoring paused when the app opens, and
 * resuming when the system asks for playback (docs/playback.md §4.2 to §4.4). The service and its player outlive a
 * test, so every test starts and ends with an empty player and an empty state file.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRestoreTest {

    private val songs = SongSourceRule {
        listOf(
            SongSourceRule.song(1, "Alpha"),
            SongSourceRule.song(2, "Bravo", LONG_FIXTURE),
            SongSourceRule.song(3, "Charlie", LONG_FIXTURE),
        )
    }
    private val store = PlaybackStateStore.get(TestFiles.targetContext)
    private val context = TestFiles.targetContext

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs)

    private lateinit var controller: MediaController

    @Before
    fun startEmpty() {
        controller = connectController()
        onMain {
            controller.pause()
            controller.stop()
            controller.clearMediaItems()
        }
        controller.waitUntil("the player is empty") { mediaItemCount == 0 }
        runBlocking { store.clear() }
    }

    @After
    fun cleanUp() {
        controller.stopAndRelease()
        runBlocking { store.clear() }
    }

    @Test
    fun pausingAndSeekingSaveThePositionAtOnce() {
        val request = QueueRequest(startSongId = 2, startRow = 1)
        controller.tap(request, "Bravo")
        controller.waitUntil("Bravo plays as the second of three") {
            isPlaying && currentMediaItem?.mediaId == "2" && mediaItemCount == 3
        }

        onMain { controller.seekTo(5_000) }
        controller.waitUntil("the seek lands") { currentPosition >= 5_000 }
        onMain { controller.pause() }
        controller.waitUntil("playback pauses") { !playWhenReady }

        eventually("the paused position is saved") {
            val saved = store.readBlocking()
            saved?.songId == 2L && saved.row == 1 && saved.queue == request && saved.positionMs in 4_000L..6_000L
        }
    }

    @Test
    fun thePositionIsSavedWhilePlayingEveryFiveSeconds() {
        controller.tap(QueueRequest(startSongId = 2, startRow = 1), "Bravo")
        controller.waitUntil("Bravo plays") { isPlaying }

        eventually("the five second tick saves a position well into the song", timeout = 12.seconds) {
            (store.readBlocking()?.positionMs ?: 0) >= 4_500
        }
    }

    @Test
    fun openingTheAppShowsTheSavedQueuePausedAtItsSongAndPosition() {
        val saved = snapshot(songId = 2, row = 1, positionMs = 2_000, repeatMode = Player.REPEAT_MODE_ONE)
        runBlocking { store.write(saved) }

        ActivityScenario.launch(MainActivity::class.java).use {
            controller.waitUntil("the app shows the saved queue without playing") {
                mediaItemCount == 3 && currentMediaItem?.mediaId == "2" && playbackState == Player.STATE_READY &&
                    !playWhenReady
            }
            assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(1)
            assertThat(onMain { controller.currentPosition }).isBetween(1_800L, 2_300L)
            assertThat(onMain { controller.repeatMode }).isEqualTo(Player.REPEAT_MODE_ONE)
            throughout("the restored queue stays paused", 1.seconds) { onMain { !controller.playWhenReady } }
        }
    }

    @Test
    fun aPlayRequestOnAnEmptyPlayerRebuildsTheSavedQueueAndStartsIt() {
        runBlocking { store.write(snapshot(songId = 2, row = 1, positionMs = 2_000)) }

        onMain { controller.play() }

        controller.waitUntil("the saved queue plays at its song") {
            isPlaying && mediaItemCount == 3 && currentMediaItem?.mediaId == "2"
        }
        assertThat(onMain { controller.currentPosition }).isGreaterThan(1_500L)
    }

    @Test
    fun aMissingSavedSongStartsAtItsRowWithoutItsOldPosition() {
        runBlocking { store.write(snapshot(songId = 99, row = 1, positionMs = 5_000)) }

        onMain { controller.play() }

        controller.waitUntil("the row's song plays instead") {
            isPlaying && mediaItemCount == 3 && currentMediaItem?.mediaId == "2"
        }
        assertThat(onMain { controller.currentPosition }).isLessThan(2_000L)
    }

    @Test
    fun aSongChangeAndTheModesAreSaved() {
        val request = QueueRequest(startSongId = 2, startRow = 1)
        controller.tap(request, "Bravo")
        controller.waitUntil("Bravo plays") { isPlaying && currentMediaItem?.mediaId == "2" }

        onMain { controller.seekToNextMediaItem() }
        controller.waitUntil("Charlie plays") { currentMediaItem?.mediaId == "3" }
        onMain {
            controller.shuffleModeEnabled = true
            controller.repeatMode = Player.REPEAT_MODE_ALL
        }

        eventually("the next song and both modes are saved") {
            val saved = store.readBlocking()
            saved?.songId == 3L && saved.row == 2 && saved.queue == request && saved.shuffle &&
                saved.repeatMode == Player.REPEAT_MODE_ALL
        }
    }

    @Test
    fun aSingleFileDoesNotReplaceTheSavedLibraryQueue() {
        val saved = snapshot(songId = 2, row = 1, positionMs = 2_000)
        runBlocking { store.write(saved) }

        onMain {
            controller.setMediaItem(fileItem(LONG_FIXTURE))
            controller.prepare()
            controller.play()
        }
        controller.waitUntil("the debug file plays") { isPlaying }
        onMain { controller.seekTo(3_000) }
        controller.waitUntil("the file seeks") { currentPosition >= 3_000 }
        onMain { controller.pause() }

        Thread.sleep(500)
        assertThat(store.readBlocking()).isEqualTo(saved)
    }

    @Test
    fun aRestoredOpenedFileThatIsGoneIsSkippedWithAMessage() {
        val errors = CopyOnWriteArrayList<String>()
        val observer = connectController(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) {
                errors += sessionError.message
            }
        })
        try {
            val missing = Uri.fromFile(java.io.File(context.cacheDir, "expired-grant.mp3"))
            val saved = PlaybackSnapshot(
                queue = QueueRequest(startSongId = 1, startRow = 0),
                openItems = listOf(
                    SavedOpenItem(1, OpenedFile(missing.toString(), "Gone", null, null, 2_000)),
                ),
                songId = 2,
                row = 2,
                positionMs = 0,
                shuffle = false,
                repeatMode = Player.REPEAT_MODE_OFF,
                title = "Bravo",
                artist = "Artist",
                album = "Album",
                durationMs = 2_000,
                uri = null,
            )
            runBlocking { store.write(saved) }

            ActivityScenario.launch(MainActivity::class.java).use {
                controller.waitUntil("the saved library queue plays without the gone file") {
                    mediaItemCount == 3 && currentMediaItem?.mediaId == "2" && !playWhenReady
                }
            }

            val expected = context.resources.getQuantityString(R.plurals.open_files_skipped, 1, 1)
            eventually("the skipped opened file is reported") { errors.any { it == expected } }
            assertThat(onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } })
                .containsExactly("1", "2", "3")
        } finally {
            onMain { observer.release() }
        }
    }

    @Test
    fun aFileOpenedFromAnotherAppGoesRightAfterTheCurrentSong() {
        val opened = insertAudio("open-with-insert.mp3")
        try {
            val request = QueueRequest(startSongId = 2, startRow = 1)
            controller.tap(request, "Bravo")
            controller.waitUntil("Bravo plays as the second of three") {
                isPlaying && currentMediaItem?.mediaId == "2" && mediaItemCount == 3
            }

            openInApp(opened).use {
                controller.waitUntil("the opened file plays right after Bravo") {
                    isPlaying && mediaItemCount == 4 && currentMediaItem?.mediaId == opened.toString()
                }
                assertThat(onMain { controller.mediaItemCount }).isEqualTo(4)
                assertThat(onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } })
                    .containsExactly("1", "2", opened.toString(), "3")
            }

            eventually("the mixed queue is saved") {
                val saved = store.readBlocking()
                saved != null && saved.songId == -1L && saved.row == 2 && saved.queue == request &&
                    saved.openItems.map { it.index to it.uri } == listOf(2 to opened.toString())
            }
        } finally {
            onMain {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            }
            deleteAudio(opened)
        }
    }

    @Test
    fun aFileOpenedFromAnotherAppStartsAQueueAndIsRestoredByASystemPlayRequest() {
        val opened = insertAudio("open-with-alone.mp3")
        try {
            openInApp(opened).use {
                controller.waitUntil("the opened file plays") {
                    isPlaying && mediaItemCount == 1 && currentMediaItem?.mediaId == opened.toString()
                }
            }

            eventually("the opened queue is saved") {
                val saved = store.readBlocking()
                saved != null && saved.queue == null && saved.songId == -1L && saved.row == 0 &&
                    saved.openItems.single().uri == opened.toString()
            }

            onMain {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            }
            controller.waitUntil("the player is empty") { mediaItemCount == 0 }
            onMain { controller.play() }

            controller.waitUntil("the saved opened file is rebuilt and plays") {
                isPlaying && mediaItemCount == 1 && currentMediaItem?.mediaId == opened.toString()
            }
        } finally {
            onMain {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            }
            deleteAudio(opened)
        }
    }

    private fun openInApp(uri: Uri): ActivityScenario<MainActivity> {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "audio/mpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return ActivityScenario.launch(intent)
    }

    /** A file of the app's own in the media index, the way a file manager would offer it. */
    private fun insertAudio(name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PiPlayerTest")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, values))
        try {
            resolver.openOutputStream(uri)!!.use { output ->
                TestFiles.assets.open(SHORT_FIXTURE).use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun deleteAudio(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    private fun snapshot(
        songId: Long,
        row: Int,
        positionMs: Long,
        shuffle: Boolean = false,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ) = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 1, startRow = 0),
        songId = songId,
        row = row,
        positionMs = positionMs,
        shuffle = shuffle,
        repeatMode = repeatMode,
        title = "Title $songId",
        artist = "Artist",
        album = "Album",
        durationMs = 2_000,
        uri = null,
    )
}
