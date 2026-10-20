package io.github.lorddetson.piplayer.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every format PiPlayer plays through the platform's decoders delivers all of a 2 s tone to the audio output
 * (docs/playback.md §1.7), including FLAC frames larger than the decoder's default input buffer in a file whose header
 * leaves the frame size unknown. The player is the service's, with its output tapped to count the decoded audio. The
 * count may run over 2 s by the padding a format keeps at its end: one AAC frame (23 ms), the encoder delay of raw AAC,
 * which has no gapless information, the last Vorbis block (43 ms), AMR's last frames. Fixtures:
 * scripts/generate_decoding_fixtures.py and scripts/generate_playback_fixtures.py.
 */
@OptIn(UnstableApi::class)
@RunWith(Parameterized::class)
class PlaybackFormatsTest(private val asset: String) {

    companion object {
        private const val TAG = "PlaybackFormatsTest"
        private const val SECONDS = 2.0

        /** Rounding to whole frames. */
        private const val SHORT_BY_AT_MOST = 0.005

        /** The padding a format keeps at its end, at most. */
        private const val LONG_BY_AT_MOST = 0.1

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun assets() = listOf(
            "decoding/mp3-44100-stereo.mp3",
            "decoding/mp3-44100-320k-stereo.mp3",
            "decoding/aac-44100-stereo.m4a",
            "decoding/vorbis-48000-mono.ogg",
            "decoding/opus-48000-stereo.opus",
            "decoding/flac-96000-24bit-stereo.flac",
            "decoding/flac-48000-16bit-5.1.flac",
            "decoding/flac-48000-24bit-stereo-big-frames.flac",
            "decoding/wav-44100-16bit-stereo.wav",
            "decoding/wav-11025-16bit-mono.wav",
            "decoding/wav-48000-24bit-stereo.wav",
            "decoding/wav-44100-float-mono.wav",
            "playback/aac-adts-44100-stereo.aac",
            "playback/amr-nb-8000-mono.amr",
            "playback/amr-wb-16000-mono.awb",
            "playback/amr-nb-8000-mono.3gp",
            "playback/amr-wb-16000-mono.3gp",
            "playback/opus-48000-stereo.webm",
            "playback/vorbis-44100-stereo.mka",
        )
    }

    private val counter = PcmCounter()
    private var player: ExoPlayer? = null

    @After
    fun release() {
        player?.let { onMain { it.release() } }
    }

    @Test
    fun deliversAllOfItsAudio() {
        val context = TestFiles.targetContext
        val item = fileItem(asset)
        val player = onMain {
            PlaybackPlayer.builder(context)
                .setRenderersFactory(PcmCapture.Renderers(context, counter))
                .build()
                .also {
                    it.setMediaItem(item)
                    it.prepare()
                    it.play()
                }
        }
        this.player = player

        player.waitUntil("$asset ends", timeout = 15.seconds) {
            playerError != null || playbackState == Player.STATE_ENDED
        }

        Log.i(TAG, "$asset: ${counter.seconds} s at ${counter.sampleRate} Hz")
        assertThat(onMain { player.playerError }).isNull()
        assertThat(counter.seconds).describedAs("seconds of decoded audio")
            .isBetween(SECONDS - SHORT_BY_AT_MOST, SECONDS + LONG_BY_AT_MOST)
    }
}
