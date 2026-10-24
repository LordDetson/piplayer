package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * All of the player's audio on its way to the output, after the encoders' delay and padding are trimmed, both channels,
 * kept across the resets of the output at every song change (docs/playback.md §8.3). [resets] are the frame counts
 * where the output was reset; [problems] what does not fit a 16-bit stream of one layout.
 */
@OptIn(UnstableApi::class)
class JoinCapture : TeeAudioProcessor.AudioBufferSink {

    private val lock = Any()
    private var samples = ShortArray(1 shl 21)
    private var count = 0
    private var channels = 0
    private val rateSet = mutableSetOf<Int>()
    private val resetList = mutableListOf<Int>()
    private val problemList = mutableListOf<String>()

    val frames: Int get() = synchronized(lock) { if (channels == 0) 0 else count / channels }
    val rates: Set<Int> get() = synchronized(lock) { rateSet.toSet() }
    val resets: List<Int> get() = synchronized(lock) { resetList.toList() }
    val problems: List<String> get() = synchronized(lock) { problemList.toList() }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = synchronized(lock) {
        if (encoding != C.ENCODING_PCM_16BIT) problemList += "encoding $encoding"
        if (channels != 0 && channels != channelCount) problemList += "$channels channels, then $channelCount"
        channels = channelCount
        rateSet += sampleRateHz
        resetList += count / channelCount
    }

    override fun handleBuffer(buffer: ByteBuffer) = synchronized(lock) {
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val size = data.remaining()
        if (count + size > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, count + size))
        data.get(samples, count, size)
        count += size
    }

    /** The first channel at [frame]. */
    fun left(frame: Int): Short = synchronized(lock) { samples[frame * channels] }

    /** The MD5 of the first [frames] frames as 16-bit little-endian samples, as the generator writes it. */
    fun md5(frames: Int): String = synchronized(lock) {
        val bytes = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples, 0, frames * channels)
        MessageDigest.getInstance("MD5").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }
}

/** Where a capture lines up with the piece: in windows before and after every join and at both ends (§8.3). */
object JoinAlignment {

    private const val WINDOW = 4096

    /** Separately encoded songs differ from the piece near their joins, so windows keep this far away. */
    private const val MARGIN = 2600

    /** More than any error seen: 1023 samples of an AAC frame, 529 of the MP3 decoder's delay. */
    private const val SEARCH = 1100

    /** At [position] in the capture the audio should be [expected] samples late, and is [found] (null: no audio). */
    data class Window(val where: String, val position: Int, val expected: Int, val found: Int?)

    fun windows(capture: JoinCapture, album: GaplessAlbum): List<Window> {
        val windows = mutableListOf(window(capture, album, "start of song 1", MARGIN, 0))
        var pieceEnd = 0
        var late = 0
        album.frames.forEachIndexed { index, frames ->
            pieceEnd += frames
            // Where the song ends in the capture, with what came late before it.
            val end = pieceEnd + late
            windows += window(capture, album, "end of song ${index + 1}", end - MARGIN - WINDOW, late)
            if (index < album.frames.lastIndex) {
                val songLate = album.late.getOrElse(index) { 0 }
                late += songLate
                windows += window(capture, album, "start of song ${index + 2}", end + songLate + MARGIN, late)
            }
        }
        return windows
    }

    private fun window(capture: JoinCapture, album: GaplessAlbum, where: String, position: Int, expected: Int): Window {
        if (position < 0 || position + WINDOW > capture.frames) return Window(where, position, expected, null)
        // The piece from SEARCH samples before to SEARCH after where the capture should line up.
        val from = position - expected - SEARCH
        val piece = DoubleArray(WINDOW + 2 * SEARCH) { GaplessSignal.left((from + it).toDouble() / album.rate) }
        val heard = DoubleArray(WINDOW) { capture.left(position + it).toDouble() }
        var energy = (0 until WINDOW).sumOf { piece[it] * piece[it] }
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (shift in 0..2 * SEARCH) {
            if (shift > 0) energy += piece[shift + WINDOW - 1].let { it * it } - piece[shift - 1].let { it * it }
            var dot = 0.0
            for (i in 0 until WINDOW) dot += heard[i] * piece[shift + i]
            val score = dot / sqrt(energy)
            if (score > bestScore) {
                bestScore = score
                best = shift
            }
        }
        // The capture at position is the piece at from + best: the audio is that many samples late.
        return Window(where, position, expected, position - (from + best))
    }
}
