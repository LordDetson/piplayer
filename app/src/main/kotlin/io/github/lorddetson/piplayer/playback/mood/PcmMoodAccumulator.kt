package io.github.lorddetson.piplayer.playback.mood

import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class TrackMood(val tempoBpm: Int?, val intensity: Float, val energy: Float)

/** Retains only short power windows while PCM passes through. */
internal class PcmMoodAccumulator {
    private val windows = DoubleArray(16 * WINDOWS_PER_SECOND)
    private var count = 0
    private var framesPerWindow = 0
    private var pendingFrames = 0
    private var pendingPower = 0.0

    /** Adds power per decoded frame and returns false when the excerpt is complete. */
    fun add(framePowers: FloatArray, sampleRate: Int): Boolean {
        if (sampleRate < WINDOWS_PER_SECOND || count == windows.size) return false
        val windowSize = (sampleRate.toDouble() / WINDOWS_PER_SECOND).roundToInt()
        if (windowSize != framesPerWindow) {
            framesPerWindow = windowSize
            pendingFrames = 0
            pendingPower = 0.0
        }
        for (power in framePowers) {
            if (power.isFinite()) pendingPower += power.coerceIn(0f, 1f)
            pendingFrames++
            if (pendingFrames == framesPerWindow) {
                windows[count++] = pendingPower / framesPerWindow
                pendingFrames = 0
                pendingPower = 0.0
                if (count == windows.size) return false
            }
        }
        return true
    }

    fun result(): TrackMood? {
        if (count < WINDOWS_PER_SECOND / 2) return null
        val power = windows.take(count).average()
        if (power < SILENCE_POWER) return null
        val intensity = ((10 * log10(power) - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0.0, 1.0).toFloat()
        val tempo = tempo()
        val energy = tempo?.let { bpm ->
            val pace = ((bpm - 58) / 132f).coerceIn(0f, 1f)
            intensity * 0.65f + pace * 0.35f
        } ?: intensity
        return TrackMood(tempo, intensity, energy)
    }

    private fun tempo(): Int? {
        if (count < 6 * WINDOWS_PER_SECOND) return null
        // A positive change in compressed power exposes attacks even in a quiet recording.
        val attacks = DoubleArray(count)
        var level = logPower(windows[0])
        for (index in 1 until count) {
            val next = logPower(windows[index])
            attacks[index] = (next - level - MIN_ATTACK).coerceAtLeast(0.0)
            level = next
        }
        if (attacks.count { it > 0 } < 4) return null
        val mean = attacks.average()
        // Smooth frame-boundary jitter, then remove the baseline to compare attack sequences.
        val envelope = DoubleArray(count) { index ->
            0.5 * attacks[index] + 0.25 * (attacks.getOrElse(index - 1) { 0.0 } + attacks.getOrElse(index + 1) { 0.0 }) - mean
        }
        val scores = DoubleArray(MAX_LAG + 2)
        for (lag in (MIN_LAG - 1)..(MAX_LAG + 1)) {
            scores[lag] = correlation(envelope, lag)
        }
        val peaks = (MIN_LAG..MAX_LAG).filter { lag -> scores[lag] >= scores[lag - 1] && scores[lag] > scores[lag + 1] }
        val strongest = peaks.maxOfOrNull { scores[it] } ?: return null
        // Prefer the first near-equivalent peak over a multiple of the same beat interval.
        val beat = peaks.firstOrNull { scores[it] >= maxOf(0.30, strongest * 0.82) } ?: return null
        val before = scores[beat - 1]
        val after = scores[beat + 1]
        val refinement = 0.5 * (before - after) / (before - 2 * scores[beat] + after)
        return (60.0 * WINDOWS_PER_SECOND / (beat + refinement)).roundToInt()
    }

    private fun logPower(power: Double) = 0.5 * ln1p(power * 500)

    private fun correlation(values: DoubleArray, lag: Int): Double {
        var product = 0.0
        var leadingPower = 0.0
        var followingPower = 0.0
        for (index in lag until values.size) {
            val leading = values[index - lag]
            val following = values[index]
            product += leading * following
            leadingPower += leading * leading
            followingPower += following * following
        }
        val scale = sqrt(leadingPower * followingPower)
        return if (scale == 0.0) 0.0 else product / scale
    }

    private companion object {
        const val WINDOWS_PER_SECOND = 100
        const val SILENCE_POWER = 1e-8
        const val QUIET_DBFS = -52.0
        const val LOUD_DBFS = -9.0
        const val MIN_ATTACK = 0.035
        const val MIN_LAG = 31
        const val MAX_LAG = 105
    }
}
