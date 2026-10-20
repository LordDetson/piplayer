package io.github.lorddetson.piplayer.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Seeking with the playback service's extractor settings (docs/playback.md §1.3). Each fixture
 * (scripts/generate_playback_fixtures.py) has 12 one-second segments, segment k a tone of 400 + 100 * k Hz, the last 6
 * with noise so that a variable bitrate is uneven. The player is the service's, with its audio output tapped and a buffer
 * of about 1 s, read in 4 KB steps: a whole fixture would otherwise sit in the buffer, and a seek inside the buffer is
 * exact whatever the file offers, so every seek here goes through the file's seek map. After a seek to the middle of a
 * segment, the tone that plays tells which segment the seek reached.
 */
@OptIn(UnstableApi::class)
@RunWith(Parameterized::class)
class SeekingTest(private val case: Case) {

    data class Case(
        val asset: String,
        val durationSeconds: Double,
        /** How far the length may be off: it is an estimate for some files. */
        val durationToleranceSeconds: Double,
        /** How many segments a seek may land away from its target: 0 where seeking is exact. */
        val landingTolerance: Int,
    ) {
        override fun toString() = asset.substringAfterLast('/')
    }

    companion object {
        private const val TAG = "SeekingTest"
        private const val SEGMENTS = 12
        private const val BASE_HZ = 400
        private const val STEP_HZ = 100

        /** Seeks go to the middle of these segments, forward and back, in both halves. */
        private val TARGETS = listOf(7, 2, 10, 1)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            // Xing with a table of contents: its table.
            Case("playback/seek-mp3-vbr-xing.mp3", 12.0, 0.05, 0),
            // Xing without a table: an index built while reading.
            Case("playback/seek-mp3-vbr-xing-without-toc.mp3", 12.0, 0.05, 0),
            // No header at all, constant bitrate: the bitrate of the first frame.
            Case("playback/seek-mp3-cbr-no-header.mp3", 12.0, 0.1, 0),
            // Raw AAC: the average frame size, an estimate.
            Case("playback/seek-aac-adts.aac", 12.0, 0.4, 1),
            // Raw AMR in one mode: every frame has the size of the first, so the estimate is exact.
            Case("playback/seek-amr-nb.amr", 12.0, 0.1, 0),
        )
    }

    private val capture = PcmCapture(seconds = 0.25)
    private var player: ExoPlayer? = null

    @After
    fun release() {
        player?.let { onMain { it.release() } }
    }

    @Test
    fun seeksLandOnTheRightSecond() {
        val context = TestFiles.targetContext
        val item = fileItem(case.asset)
        val player = onMain {
            PlaybackPlayer.builder(context)
                .setRenderersFactory(PcmCapture.Renderers(context, capture))
                .setMediaSourceFactory(
                    ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context), PlaybackPlayer.extractors())
                        .setContinueLoadingCheckIntervalBytes(4 * 1024),
                )
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 1_000, 250, 250).build())
                .build()
                .also {
                    it.setMediaItem(item)
                    it.prepare()
                    it.play()
                }
        }
        this.player = player
        player.waitUntil("${case.asset} plays") { isPlaying }

        assertThat(onMain { player.isCurrentMediaItemSeekable }).describedAs("seekable").isTrue()
        assertThat(onMain { player.duration } / 1000.0).describedAs("length in seconds")
            .isCloseTo(case.durationSeconds, within(case.durationToleranceSeconds))

        val landings = TARGETS.associateWith { target ->
            val pcm = capture.arm()
            onMain { player.seekTo(target * 1000L + 500L) }
            segmentOf(pcm.get(5, TimeUnit.SECONDS))
        }
        Log.i(TAG, "${case.asset}: target segment to landing segment $landings")
        for ((target, landed) in landings) {
            assertThat(abs(landed - target)).describedAs("seek to segment $target landed in $landed")
                .isLessThanOrEqualTo(case.landingTolerance)
        }
    }

    /**
     * The segment whose tone is strongest in [pcm], by the Goertzel filter, after skipping 80 ms: right after a seek
     * the MP3 decoder lacks the bit reservoir of the frames before, and the first frame or two of a variable-bitrate
     * file come out garbled (docs/playback.md §1.3).
     */
    private fun segmentOf(pcm: Pcm): Int {
        val skip = pcm.sampleRate * 8 / 100
        val samples = pcm.samples.copyOfRange(skip, pcm.samples.size)
        return (0 until SEGMENTS).maxBy { k -> power(samples, pcm.sampleRate, (BASE_HZ + STEP_HZ * k).toDouble()) }
    }

    private fun power(samples: ShortArray, sampleRate: Int, frequency: Double): Double {
        val coefficient = 2 * cos(2 * PI * frequency / sampleRate)
        var previous = 0.0
        var beforePrevious = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - beforePrevious
            beforePrevious = previous
            previous = current
        }
        return previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious
    }
}
