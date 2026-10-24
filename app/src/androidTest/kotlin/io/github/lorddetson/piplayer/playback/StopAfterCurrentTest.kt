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

    /** Waits until playback has stopped after a song and moved to [index], paused at its start. */
    private fun expectStoppedOn(index: Int) {
        controller.waitUntil("playback stops on song $index", timeout = 5.seconds) {
            !playWhenReady && currentMediaItemIndex == index && currentPosition < 500
        }
        assertThat(pauseReasons).containsExactly(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        controller.holdsThroughout("the next song does not play", 1.seconds) { !isPlaying && currentPosition < 500 }
    }

    @Test
    fun playbackStopsOnTheNextSongAndTheToggleTurnsItselfOff() {
        playQueue(SHORT_FIXTURE, SHORT_FIXTURE, LONG_FIXTURE)

        assertThat(stopAfterCurrent(true)).isEqualTo(SessionResult.RESULT_SUCCESS)
        eventually("the toggle is on") { isOn() }

        expectStoppedOn(1)
        eventually("the toggle is off") { !isOn() }

        // Play starts the next song from its start, and the song after it follows without another stop.
        onMain { controller.play() }
        controller.waitUntil("the second song plays") { isPlaying && currentMediaItemIndex == 1 }
        controller.waitUntil("the third song follows", timeout = 5.seconds) { currentMediaItemIndex == 2 }
        assertThat(onMain { controller.playWhenReady }).isTrue()
        assertThat(pauseReasons).hasSize(1)
    }

    @Test
    fun aSkipKeepsItAndItStopsAfterTheSongSkippedTo() {
        playQueue(LONG_FIXTURE, SHORT_FIXTURE, LONG_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        onMain { controller.seekToNextMediaItem() }

        controller.holdsThroughout("the toggle stays on", 1.seconds) { PlaybackExtras.from(sessionExtras).stopAfterCurrent }
        expectStoppedOn(2)
        eventually("the toggle is off") { !isOn() }
    }

    @Test
    fun aSeekWithinTheSongKeepsIt() {
        playQueue(LONG_FIXTURE, SHORT_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        onMain { controller.seekTo(10_500) }

        expectStoppedOn(1)
    }

    @Test
    fun withRepeatOneItStopsOnTheSameSongFromItsStart() {
        playQueue(SHORT_FIXTURE, LONG_FIXTURE)
        onMain { controller.repeatMode = Player.REPEAT_MODE_ONE }
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        expectStoppedOn(0)
    }

    @Test
    fun afterTheLastSongPlaybackStaysAtItsEnd() {
        playQueue(SHORT_FIXTURE)
        stopAfterCurrent(true)
        eventually("the toggle is on") { isOn() }

        controller.waitUntil("playback stops", timeout = 5.seconds) { !playWhenReady }
        assertThat(onMain { controller.currentMediaItemIndex }).isEqualTo(0)
        assertThat(onMain { controller.currentPosition }).isGreaterThanOrEqualTo(1_800L)
        eventually("the toggle is off") { !isOn() }
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
