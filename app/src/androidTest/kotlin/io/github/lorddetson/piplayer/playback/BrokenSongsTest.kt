package io.github.lorddetson.piplayer.playback

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.SongSourceRule.Companion.fake
import io.github.lorddetson.piplayer.playback.SongSourceRule.Companion.missing
import io.github.lorddetson.piplayer.playback.SongSourceRule.Companion.song
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Songs that cannot be played are skipped, and playback stops after 5 in a row, at a broken last song, or once every
 * song of a short queue failed, with a message to the controllers (docs/playback.md §2.4).
 */
@RunWith(AndroidJUnit4::class)
class BrokenSongsTest {

    @get:Rule
    val songs = SongSourceRule()

    private val errors = CopyOnWriteArrayList<SessionError>()
    private lateinit var controller: MediaController

    private val text = "This is not an MP3 file.\n".toByteArray()

    /** A small JPEG: Media3 recognizes images, so it opens without an error but has no audio to play. */
    private val image: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Before
    fun connect() {
        controller = connectController(object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) {
                errors += sessionError
            }
        })
    }

    @After
    fun disconnect() {
        // The service's player outlives the test: leave it as other tests expect it.
        onMain { controller.repeatMode = Player.REPEAT_MODE_OFF }
        controller.stopAndRelease()
    }

    @Test
    fun brokenSongsAreSkipped() {
        songs.songs = listOf(missing(1, "A"), fake(2, "B", text), fake(3, "C", image), song(4, "D"))

        controller.tap(QueueRequest(startSongId = 1, startRow = 0))

        controller.waitUntil("D plays") { isPlaying && currentMediaItem?.mediaId == "4" }
        assertThat(errors).isEmpty()
    }

    @Test
    fun fiveBrokenSongsInARowStopPlayback() {
        songs.songs = List(6) { missing(it + 1L, "Song 0${it + 1}") } + song(7, "Song 07")

        controller.tap(QueueRequest(startSongId = 1, startRow = 0))

        waitUntilStopped()
        assertThat(onMain { controller.currentMediaItem?.mediaId }).isEqualTo("5")
        assertStoppedAfter(5)
        throughout("playback stays stopped", 2.seconds) { onMain { !controller.isPlaying } }
    }

    @Test
    fun aSongThatOpensEndsTheStreak() {
        // The list is sorted by title, so the titles keep this order.
        songs.songs = List(4) { missing(it + 1L, "Song 0${it + 1}") } + song(5, "Song 05") +
            List(4) { missing(it + 6L, "Song 0${it + 6}") } + song(10, "Song 10")

        controller.tap(QueueRequest(startSongId = 1, startRow = 0))

        controller.waitUntil("the second good song plays", timeout = 15.seconds) {
            isPlaying && currentMediaItem?.mediaId == "10"
        }
        assertThat(errors).isEmpty()
    }

    @Test
    fun aBrokenLastSongStopsPlayback() {
        songs.songs = listOf(song(1, "A"), missing(2, "B"))

        controller.tap(QueueRequest(startSongId = 2, startRow = 1))

        waitUntilStopped()
        assertStoppedAfter(1)
    }

    @Test
    fun aQueueWhoseSongsAllFailStopsEvenWithRepeat() {
        songs.songs = listOf(missing(1, "A"), missing(2, "B"))
        onMain { controller.repeatMode = Player.REPEAT_MODE_ALL }

        controller.tap(QueueRequest(startSongId = 1, startRow = 0))

        waitUntilStopped()
        assertStoppedAfter(2)
    }

    @Test
    fun aSongWithNoAudioAtTheEndStopsAndPlayDoesNotPlaySilence() {
        songs.songs = listOf(song(1, "A"), fake(2, "B", image))

        controller.tap(QueueRequest(startSongId = 2, startRow = 1))

        controller.waitUntil("playback stops", timeout = 20.seconds) { !playWhenReady && errors.isNotEmpty() }
        assertStoppedAfter(1)
        errors.clear()

        onMain { controller.play() }

        controller.waitUntil("it stops again", timeout = 20.seconds) { errors.isNotEmpty() }
        assertStoppedAfter(1)
        assertThat(onMain { controller.isPlaying }).isFalse()
    }

    @Test
    fun playAfterAStopTriesTheSongAgainAndCountsAfresh() {
        songs.songs = List(6) { missing(it + 1L, "Song 0${it + 1}") } + song(7, "Song 07")
        controller.tap(QueueRequest(startSongId = 1, startRow = 0))
        waitUntilStopped()
        assertStoppedAfter(5)
        errors.clear()

        onMain { controller.play() }

        // Song 5 fails again and the count starts over, so playback moves on through song 6 to song 7.
        controller.waitUntil("song 7 plays", timeout = 20.seconds) { isPlaying && currentMediaItem?.mediaId == "7" }
        assertThat(errors).isEmpty()
    }

    private fun waitUntilStopped() = controller.waitUntil("playback stops", timeout = 20.seconds) {
        playerError != null && !playWhenReady
    }

    private fun assertStoppedAfter(failures: Int) {
        controller.waitUntil("the message arrives") { errors.isNotEmpty() }
        val error = errors.single()
        assertThat(error.code).isEqualTo(SessionError.ERROR_IO)
        assertThat(error.extras.getInt(PlaybackService.EXTRA_FAILURES)).isEqualTo(failures)
        assertThat(error.message).contains(failures.toString())
    }
}
