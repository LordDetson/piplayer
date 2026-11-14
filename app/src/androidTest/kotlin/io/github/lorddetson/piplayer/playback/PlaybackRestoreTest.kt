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
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.expect
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.slowRunnerPause
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
    fun aShuffledQueueComesBackInTheSameOrderAtTheSameSong() {
        songs.songs = (1L..8L).map { SongSourceRule.song(it, "Song $it", LONG_FIXTURE) }
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Song 1")
        controller.waitUntil("the queue plays") { isPlaying && mediaItemCount == 8 }
        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        onMain { repeat(2) { controller.seekToNextMediaItem() } }
        val order = onMain { shuffledIds() }
        controller.waitUntil("the third song of the order plays") { currentMediaItem?.mediaId == order[2] }
        onMain { controller.pause() }
        eventually("the shuffled queue is saved") {
            val saved = store.readBlocking()
            saved != null && saved.shuffle && saved.songId.toString() == order[2] && saved.queueRevision > 0
        }

        // As after the end of the process: the player forgets its queue and its shuffle, the files stay.
        onMain {
            controller.stop()
            controller.clearMediaItems()
            controller.shuffleModeEnabled = false
        }
        controller.waitUntil("the player is empty") { mediaItemCount == 0 && !shuffleModeEnabled }

        ActivityScenario.launch(MainActivity::class.java).use {
            controller.waitUntil("the saved queue is back") { mediaItemCount == 8 && currentMediaItem?.mediaId == order[2] }
            assertThat(onMain { controller.shuffleModeEnabled }).isTrue()
            assertThat(onMain { shuffledIds() }).isEqualTo(order)
        }
    }

    /** The media IDs of the queue in list order. */
    private fun mediaIds(): List<String> = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }

    /** The media IDs of the queue in its shuffled play order. */
    private fun shuffledIds(): List<String> {
        val timeline = controller.currentTimeline
        val ids = mutableListOf<String>()
        var index = timeline.getFirstWindowIndex(true)
        val window = androidx.media3.common.Timeline.Window()
        while (index != androidx.media3.common.C.INDEX_UNSET) {
            ids += timeline.getWindow(index, window).mediaItem.mediaId
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return ids
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
        // The store adds the song's track reference from the library when it writes (§4.8).
        assertThat(store.readBlocking()?.copy(current = null, libraryId = null)).isEqualTo(saved)
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

            SavedStates(store).use { saves ->
                val plays = controller.expect("the opened file plays right after Bravo", capture = { mediaIds() }) {
                    isPlaying && mediaItemCount == 4 && currentMediaItem?.mediaId == opened.toString()
                }
                openInApp(opened).use {
                    assertThat(plays.await()).containsExactly("1", "2", opened.toString(), "3")
                }

                saves.await("the mixed queue is saved") { saved ->
                    saved != null && saved.songId == -1L && saved.row == 2 && saved.queue == request &&
                        saved.openItems.map { it.index to it.uri } == listOf(2 to opened.toString())
                }
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
    fun underShuffleAFileOpenedFromAnotherAppPlaysRightAfterTheCurrentSongInThePlayOrder() {
        songs.songs = (1L..20L).map { SongSourceRule.song(it, "Song %02d".format(it), LONG_FIXTURE) }
        val opened = insertAudio("open-with-shuffled.mp3")
        try {
            controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Song 01")
            controller.waitUntil("the queue plays") { isPlaying && mediaItemCount == 20 }
            onMain { controller.shuffleModeEnabled = true }
            controller.waitUntil("shuffle is on") { shuffleModeEnabled }
            val before = onMain { shuffledIds() }

            val plays = controller.expect("the opened file plays", capture = { shuffledIds() }) {
                mediaItemCount == 21 && currentMediaItem?.mediaId == opened.toString()
            }
            openInApp(opened).use {
                // Nothing of the order is skipped: the opened file stands between the old current song and the rest.
                assertThat(plays.await()).containsExactlyElementsOf(listOf(before[0], opened.toString()) + before.drop(1))
            }
        } finally {
            onMain {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
                controller.shuffleModeEnabled = false
            }
            deleteAudio(opened)
        }
    }

    @Test
    fun aFileOpenedFromAnotherAppStartsAQueueAndIsRestoredByASystemPlayRequest() {
        val opened = insertAudio("open-with-alone.mp3")
        try {
            SavedStates(store).use { saves ->
                val plays = controller.expect("the opened file plays") {
                    isPlaying && mediaItemCount == 1 && currentMediaItem?.mediaId == opened.toString()
                }
                openInApp(opened).use { plays.await() }

                saves.await("the opened queue is saved") { saved ->
                    saved != null && saved.queue == null && saved.songId == -1L && saved.row == 0 &&
                        saved.openItems.singleOrNull()?.uri == opened.toString()
                }
            }

            onMain {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            }
            controller.waitUntil("the player is empty") { mediaItemCount == 0 }
            val rebuilt = controller.expect("the saved opened file is rebuilt and plays") {
                isPlaying && mediaItemCount == 1 && currentMediaItem?.mediaId == opened.toString()
            }
            onMain { controller.play() }
            rebuilt.await()
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
        // On a slow runner the test gets back from starting the activity late, while the opened file already plays.
        return ActivityScenario.launch<MainActivity>(intent).also { slowRunnerPause() }
    }

    /**
     * A file of the app's own in the media index, the way a file manager would offer it. A long one: a 2 s file could
     * be over before a slow runner's main thread passes on that it plays.
     */
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
                TestFiles.assets.open(LONG_FIXTURE).use { input -> input.copyTo(output) }
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
