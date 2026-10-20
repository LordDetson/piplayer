package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A prompt from PiPlayer's own process that allows ducking: the platform leaves the volume to the player, which lowers
 * it to 20% and restores it afterwards (docs/playback.md §1.2). The player is the service's, with its output volume
 * recorded.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DuckingTest {

    private val audioManager = TestFiles.targetContext.getSystemService(AudioManager::class.java)

    @Volatile
    private var volume = 1f
    private var player: ExoPlayer? = null
    private var request: AudioFocusRequest? = null

    @After
    fun cleanUp() {
        request?.let(audioManager::abandonAudioFocusRequest)
        player?.let { onMain { it.release() } }
    }

    @Test
    fun aShortPromptLowersTheVolumeToAFifthUntilItEnds() {
        val context = TestFiles.targetContext
        val item = fileItem(LONG_FIXTURE)
        val player = onMain {
            PlaybackPlayer.builder(context).setRenderersFactory(VolumeRecordingRenderers(context)).build().also {
                it.setMediaItem(item)
                it.prepare()
                it.play()
            }
        }
        this.player = player
        player.waitUntil("playback starts") { isPlaying }

        request = AudioFocus.take(audioManager, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        eventually("the volume drops to a fifth") { volume == 0.2f }

        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
        eventually("the volume comes back") { volume == 1f }
        player.waitUntil("playback goes on") { isPlaying }
    }

    /** The service's renderers, with the volume the player sets on its output recorded. */
    private inner class VolumeRecordingRenderers(context: Context) : PlaybackRenderers(context) {

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
            override fun setVolume(volume: Float) {
                this@DuckingTest.volume = volume
                super.setVolume(volume)
            }
        }
    }
}
