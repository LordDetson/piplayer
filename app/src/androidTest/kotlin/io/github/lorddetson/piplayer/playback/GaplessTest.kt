package io.github.lorddetson.piplayer.playback

import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every album of the gapless fixture plays as the piece it was cut from (docs/playback.md §8.1, §8.3): the joins line
 * up to the sample, the FLAC album bit for bit, one audio output serves it and every song change is automatic. The AAC
 * that ffmpeg writes is a known limitation: each song ends late by the rest of its last frame, as fixture.json says.
 * The player is the service's, with its output copied after trimming.
 */
@OptIn(UnstableApi::class)
@RunWith(Parameterized::class)
class GaplessTest(private val album: GaplessAlbum) {

    companion object {
        private const val TAG = "GaplessTest"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun albums() = GaplessAlbum.all()

        /** The emulators' audio runs on their host, whose hiccups show as underruns. */
        val onEmulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
    }

    private val capture = JoinCapture()
    private val audioOutputs = AtomicInteger()
    private val underruns = AtomicInteger()
    private val decoders = CopyOnWriteArrayList<String>()
    private val transitions = CopyOnWriteArrayList<Int>()
    private var player: ExoPlayer? = null

    @After
    fun release() {
        player?.let { onMain { it.release() } }
    }

    @Test
    fun playsAsThePieceItWasCutFrom() {
        val context = TestFiles.targetContext
        val items = album.files.map(::fileItem)
        val player = onMain {
            PlaybackPlayer.builder(context)
                .setRenderersFactory(PcmCapture.Renderers(context, capture))
                .build()
                .also {
                    it.addAnalyticsListener(listener)
                    it.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            transitions += reason
                        }
                    })
                    it.setMediaItems(items)
                    it.prepare()
                    it.play()
                }
        }
        this.player = player

        player.waitUntil("$album ends", timeout = 40.seconds) {
            playerError != null || playbackState == Player.STATE_ENDED
        }

        val windows = JoinAlignment.windows(capture, album)
        Log.i(
            TAG,
            "$album: ${capture.frames} frames for ${album.length}, decoders $decoders, audio outputs $audioOutputs, " +
                "underruns $underruns, output resets ${capture.resets}, windows $windows",
        )
        assertThat(onMain { player.playerError }).isNull()
        assertThat(capture.problems).isEmpty()
        assertThat(capture.rates).containsExactly(album.rate)
        // Exactly: padding left at the end of the last song would play after the piece.
        assertThat(capture.frames).describedAs("frames played").isEqualTo(album.length + album.late.sum())
        assertThat(windows.map { "${it.where}: ${it.found}" }).describedAs("samples late")
            .containsExactlyElementsOf(windows.map { "${it.where}: ${it.expected}" })
        album.md5?.let { assertThat(capture.md5(album.length)).describedAs("MD5 of the piece").isEqualTo(it) }
        assertThat(transitions).containsExactlyElementsOf(
            listOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) +
                List(album.files.size - 1) { Player.MEDIA_ITEM_TRANSITION_REASON_AUTO },
        )
        assertThat(audioOutputs.get()).describedAs("audio outputs").isEqualTo(1)
        if (!onEmulator) assertThat(underruns.get()).describedAs("underruns").isZero()
    }

    private val listener = object : AnalyticsListener {
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
            audioOutputs.incrementAndGet()
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            underruns.incrementAndGet()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            decoders += decoderName
        }
    }
}
