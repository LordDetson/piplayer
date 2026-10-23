package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headset and Bluetooth keys arrive as media button events on the platform session (docs/playback.md §4.5). Next,
 * previous and the default double tap of play/pause all reach the one player.
 */
@RunWith(AndroidJUnit4::class)
class HeadsetButtonsTest {

    private val context = TestFiles.targetContext
    private lateinit var controller: MediaController
    private lateinit var browser: MediaBrowser
    private lateinit var platform: android.media.session.MediaController

    @Before
    fun playThreeSongs() {
        controller = connectController()
        onMain {
            controller.setMediaItems(List(3) { fileItem(LONG_FIXTURE) })
            controller.prepare()
            controller.play()
        }
        controller.waitUntil("the first of three songs plays") {
            isPlaying && mediaItemCount == 3 && currentMediaItemIndex == 0
        }

        val connected = CountDownLatch(1)
        browser = onMain {
            MediaBrowser(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null,
            ).also { it.connect() }
        }
        assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
        platform = onMain { android.media.session.MediaController(context, browser.sessionToken) }
        eventually("the platform session is playing") {
            onMain { platform.playbackState?.state == PlaybackState.STATE_PLAYING }
        }
    }

    @After
    fun disconnect() {
        onMain { browser.disconnect() }
        controller.stopAndRelease()
    }

    @Test
    fun nextAndPreviousMoveThroughTheQueue() {
        press(KeyEvent.KEYCODE_MEDIA_NEXT)
        controller.waitUntil("next moves to the second song") { currentMediaItemIndex == 1 }

        // Within the first three seconds the previous button moves to the previous song (docs/playback.md §3.6).
        onMain { platform.transportControls.seekTo(1_000) }
        controller.waitUntil("the second song is one second in") { currentPosition >= 1_000 }
        press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        controller.waitUntil("previous returns to the first song") { currentMediaItemIndex == 0 }
    }

    @Test
    fun aDoubleTapOfPlayPauseSkipsForwardAndKeepsPlaying() {
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        Thread.sleep(100)
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        controller.waitUntil("the double tap skips to the second song") {
            currentMediaItemIndex == 1 && isPlaying
        }
        assertThat(onMain { controller.playWhenReady }).isTrue()
    }

    private fun press(keyCode: Int) = onMain {
        platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
