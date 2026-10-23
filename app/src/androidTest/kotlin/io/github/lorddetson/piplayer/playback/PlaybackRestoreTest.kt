package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
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
