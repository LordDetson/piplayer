package io.github.lorddetson.piplayer.demo

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt

/** Original melodies synthesized as small, seekable PCM files for the offline demo. */
internal object DemoAudio {
    enum class Sample(val id: String, val durationMs: Long) {
        MORNING_STEPS("morning-steps", 8_000),
        QUIET_ORBIT("quiet-orbit", 10_000),
    }

    val samples: List<Sample> = Sample.entries

    /** Writes one complete WAV. The caller owns [output] and chooses where to store the file. */
    fun writeSample(sample: Sample, output: OutputStream) {
        val score = when (sample) {
            Sample.MORNING_STEPS -> morningSteps
            Sample.QUIET_ORBIT -> quietOrbit
        }
        val frames = (sample.durationMs * SAMPLE_RATE / 1_000).toInt()
        val dataSize = frames * BYTES_PER_FRAME
        val file = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        file.put("RIFF".toByteArray(Charsets.US_ASCII))
        file.putInt(dataSize + HEADER_BYTES - 8)
        file.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        file.putInt(16) // PCM format chunk length.
        file.putShort(1) // PCM format.
        file.putShort(1) // Mono.
        file.putInt(SAMPLE_RATE)
        file.putInt(SAMPLE_RATE * BYTES_PER_FRAME)
        file.putShort(BYTES_PER_FRAME.toShort())
        file.putShort(16)
        file.put("data".toByteArray(Charsets.US_ASCII))
        file.putInt(dataSize)

        val lead = Voice(score.melody, frames, score.leadGain, overtone = 2.0, overtoneGain = 0.18)
        val bass = Voice(score.bass, frames, gain = 0.10, overtone = 1.5, overtoneGain = 0.24)
        repeat(frames) { frame ->
            val amplitude = lead.at(frame) + bass.at(frame)
            file.putShort((amplitude * Short.MAX_VALUE).roundToInt().toShort())
        }
        output.write(file.array())
    }

    private const val SAMPLE_RATE = 22_050
    private const val BYTES_PER_FRAME = 2
    private const val HEADER_BYTES = 44
    private const val REST = 0
    private const val SINE_SIZE = 2_048

    private val sine = DoubleArray(SINE_SIZE) { i -> StrictMath.sin(2 * PI * i / SINE_SIZE) }

    private fun wave(cycles: Double): Double = sine[(cycles * SINE_SIZE).toInt() and (SINE_SIZE - 1)]

    private class Voice(
        notes: IntArray,
        frames: Int,
        private val gain: Double,
        private val overtone: Double,
        private val overtoneGain: Double,
    ) {
        private val stepFrames = frames / notes.size
        private val cyclesPerFrame = DoubleArray(notes.size) { index ->
            val note = notes[index]
            if (note == REST) 0.0 else 440 * StrictMath.pow(2.0, (note - 69) / 12.0) / SAMPLE_RATE
        }

        init {
            require(frames % notes.size == 0)
        }

        fun at(frame: Int): Double {
            val frequency = cyclesPerFrame[frame / stepFrames]
            if (frequency == 0.0) return 0.0
            val position = frame % stepFrames
            val phase = position * frequency
            val envelope = minOf(1.0, position / 420.0, (stepFrames - 1 - position) / 2_200.0)
            val decay = 1.0 - 0.38 * position / stepFrames
            return gain * envelope * decay * (wave(phase) + overtoneGain * wave(phase * overtone))
        }
    }

    private data class Score(val melody: IntArray, val bass: IntArray, val leadGain: Double)

    private val morningSteps = Score(
        melody = intArrayOf(62, 66, 69, 71, 67, 71, 74, 69, 76, 73, 69, 64, 66, 69, 74, 62),
        bass = intArrayOf(38, 43, 45, 38),
        leadGain = 0.21,
    )

    private val quietOrbit = Score(
        melody = intArrayOf(69, 72, REST, 65, 67, 64, REST, 67, 64, 60),
        bass = intArrayOf(41, 43, 45, 36),
        leadGain = 0.16,
    )
}
