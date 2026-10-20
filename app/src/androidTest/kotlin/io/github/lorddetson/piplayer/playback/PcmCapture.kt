package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * Counts the decoded audio frames of a stream played from the start without seeks. The output is reset when the stream
 * starts, at its end, and sometimes in between (a change of the decoder's output format, a timing correction); every
 * frame passes once, so the stretches between resets add up.
 */
@OptIn(UnstableApi::class)
class PcmCounter : TeeAudioProcessor.AudioBufferSink {

    @Volatile
    var sampleRate = 0
        private set

    @Volatile
    private var frames = 0L

    private var frameSize = 0

    /** The decoded audio so far. */
    val seconds: Double get() = frames.toDouble() / sampleRate

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        check(sampleRate == 0 || sampleRate == sampleRateHz) { "The rate changed from $sampleRate to $sampleRateHz" }
        sampleRate = sampleRateHz
        frameSize = channelCount * Util.getPcmFrameSize(encoding, 1)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        frames += buffer.remaining() / frameSize
    }
}

/** The first channel of the decoded audio. */
class Pcm(val sampleRate: Int, val samples: ShortArray)

/**
 * Copies the player's decoded audio on its way to the output: after [arm], the audio from the next reset of the output
 * (a seek) on, [seconds] of it. A reset during the copy starts it again.
 */
@OptIn(UnstableApi::class)
class PcmCapture(private val seconds: Double) : TeeAudioProcessor.AudioBufferSink {

    private val lock = Any()
    private var armed = false
    private var capturing = false
    private var channels = 0
    private var sampleRate = 0
    private var samples = ShortArray(0)
    private var count = 0
    private var result = CompletableFuture<Pcm>()

    fun arm(): Future<Pcm> = synchronized(lock) {
        armed = true
        capturing = false
        result = CompletableFuture()
        result
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = synchronized(lock) {
        if (!armed && !capturing) return
        check(encoding == C.ENCODING_PCM_16BIT) { "Expected 16-bit output, got encoding $encoding" }
        armed = false
        capturing = true
        sampleRate = sampleRateHz
        channels = channelCount
        samples = ShortArray((seconds * sampleRateHz).toInt())
        count = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) = synchronized(lock) {
        if (!capturing) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (data.remaining() >= 2 * channels && count < samples.size) {
            samples[count++] = data.short
            data.position(data.position() + 2 * (channels - 1))
        }
        if (count == samples.size) {
            capturing = false
            result.complete(Pcm(sampleRate, samples))
        }
    }

    /** The playback service's renderers, with the audio output passing through [capture]. */
    class Renderers(context: Context, private val capture: TeeAudioProcessor.AudioBufferSink) :
        PlaybackRenderers(context) {

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(capture)))
            .build()
    }
}
