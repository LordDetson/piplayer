package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.holdsThroughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAfterArgs
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** "Stop after current track" through the playback service (docs/playback.md §6.4). */
@RunWith(AndroidJUnit4::class)
class StopAfterCurrentTest {

    private lateinit var controller: MediaController

    /** The reasons of every pause the controller sees. */
    private val pauseReasons = CopyOnWriteArrayList<Int>()

    @Before
    fun connect() {
        controller = connectController()
        onMain {
            controller.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) pauseReasons += reason
                }
            })
        }
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun playQueue(vararg assets: String) {
        val items = assets.map(::fileItem)
        onMain {
            controller.setMediaItems(items)
            controller.prepare()
            controller.play()
        }
        controller.waitUntil("the first song plays") { isPlaying && currentMediaItemIndex == 0 }
    }

    private fun stopAfterCurrent(enabled: Boolean): Int = onMain {
        controller.sendCustomCommand(PlaybackSessionCallback.STOP_AFTER_CURRENT_COMMAND, stopAfterArgs(enabled))
    }.get(5, TimeUnit.SECONDS).resultCode

    private fun isOn() = PlaybackExtras.from(onMain { controller.sessionExtras }).stopAfterCurrent

    @Test
    fun playbackPausesAtTheEndOfTheSongAndTheToggleTurnsItselfOff() {
        playQueue(SHORT_FIXTURE, SHORT_FIXTURE, LONG_FIXTURE)

        assertThat(stopAfterCurrent(true)).isEqualTo(SessionResult.RESULT_SUCCESS)
        eventually("the toggle is on") { isOn() }

        controller.waitUntil("playback pauses", timeout = 5.seconds) { !playWhenReady }
        assertThat(pauseReasons).containsExactly(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(0)
        assertThat(onMain { controller.currentPosition }).isGreaterThanOrEqualTo(1_800L)
        eventually("the toggle is off") { !isOn() }

        // Play goes on with the next song, and the song after it follows without another pause.
        onMain { controller.play() }
        controller.waitUntil("the second song plays") { isPlaying && currentMediaItemIndex == 1 }
        controller.waitUntil("the third song follows", timeout = 5.seconds) { currentMediaItemIndex == 2 }
        assertThat(onMain { controller.playWhenReady }).isTrue()
        assertThat(pauseReasons).hasSize(1)
    }

    @Test
    fun aSkipTurnsItOff() {
        playQueue(LONG_FIXTURE, SHORT_FIXTURE, LONG_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        onMain { controller.seekToNextMediaItem() }

        eventually("the toggle is off") { !isOn() }
        controller.waitUntil("the skipped-to song plays into the next one", timeout = 5.seconds) {
            currentMediaItemIndex == 2 && playWhenReady
        }
        assertThat(pauseReasons).isEmpty()
    }

    @Test
    fun aSeekWithinTheSongKeepsIt() {
        playQueue(LONG_FIXTURE, SHORT_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        onMain { controller.seekTo(10_500) }

        controller.waitUntil("playback pauses at the end of the song", timeout = 5.seconds) { !playWhenReady }
        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(0)
        assertThat(pauseReasons).containsExactly(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
    }

    @Test
    fun turningItOffLetsPlaybackGoOn() {
        playQueue(SHORT_FIXTURE, LONG_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        stopAfterCurrent(false)

        eventually("the toggle is off") { !isOn() }
        controller.waitUntil("the next song follows", timeout = 5.seconds) { currentMediaItemIndex == 1 }
        controller.holdsThroughout("playback goes on", 1.seconds) { playWhenReady }
    }

    @Test
    fun itTurnsOnOnlyWithACurrentSong() {
        stopAfterCurrent(true)

        controller.holdsThroughout("the toggle stays off", 1.seconds) { !PlaybackExtras.from(sessionExtras).stopAfterCurrent }
    }
}
