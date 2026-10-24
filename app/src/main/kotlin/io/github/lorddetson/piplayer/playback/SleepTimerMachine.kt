package io.github.lorddetson.piplayer.playback

/**
 * The sleep timer's decisions on a clock it is given (docs/playback.md §6.3): it says what to do with the player and
 * when to look again; [SleepTimer] does it. Every decision is made from the clock, so a tick that comes late, after
 * deep sleep, catches up rather than drifting.
 */
class SleepTimerMachine(private val fadeMs: Long = FadeCurve.FADE_MS) {

    enum class Phase { OFF, COUNTING, FADING, FINISHING }

    sealed interface Effect {
        data class SetVolume(val volume: Float) : Effect

        /** Pause, then set the volume back to [volume] in the same turn. */
        data class PauseAndRestore(val volume: Float) : Effect

        data class RestoreVolume(val volume: Float) : Effect

        /** Pause at the end of the song that plays. */
        data object ArmSongEnd : Effect

        data object DisarmSongEnd : Effect
    }

    var phase = Phase.OFF
        private set

    /** When the timer is up, in the clock's milliseconds; null when off. */
    var deadlineMs: Long? = null
        private set

    var finishTrack = false
        private set

    /** The volume the fade started from, which comes back when it ends. */
    private var fadeFrom = 1f

    /** A new timer of [durationMs] from [nowMs]; ends a running one first. A duration of 0 or less only cancels. */
    fun start(nowMs: Long, durationMs: Long, finishTrack: Boolean): List<Effect> {
        val effects = cancel()
        if (durationMs <= 0) return effects
        phase = Phase.COUNTING
        deadlineMs = nowMs + durationMs
        this.finishTrack = finishTrack
        return effects
    }

    fun cancel(): List<Effect> {
        val effects = when (phase) {
            Phase.FADING -> listOf(Effect.RestoreVolume(fadeFrom))
            Phase.FINISHING -> listOf(Effect.DisarmSongEnd)
            Phase.OFF, Phase.COUNTING -> emptyList()
        }
        off()
        return effects
    }

    /** Time has passed: [playing] says whether playback is on, [volume] is the player's volume now. */
    fun tick(nowMs: Long, playing: Boolean, volume: Float): List<Effect> {
        val deadline = deadlineMs ?: return emptyList()
        return when (phase) {
            Phase.OFF, Phase.FINISHING -> emptyList()
            Phase.COUNTING -> when {
                nowMs >= deadline && !playing -> {
                    // Nothing plays: the timer's aim already holds.
                    off()
                    emptyList()
                }
                nowMs >= deadline && finishTrack -> {
                    phase = Phase.FINISHING
                    listOf(Effect.ArmSongEnd)
                }
                nowMs >= deadline -> {
                    // A tick that comes after the whole fade, from deep sleep.
                    off()
                    listOf(Effect.PauseAndRestore(volume))
                }
                !finishTrack && playing && nowMs >= deadline - fadeMs -> {
                    phase = Phase.FADING
                    fadeFrom = volume
                    listOf(Effect.SetVolume(FadeCurve.volume(fadeFrom, fraction(nowMs, deadline))))
                }
                else -> emptyList()
            }
            Phase.FADING -> when {
                nowMs >= deadline -> {
                    val restore = fadeFrom
                    off()
                    listOf(Effect.PauseAndRestore(restore))
                }
                !playing -> paused()
                else -> listOf(Effect.SetVolume(FadeCurve.volume(fadeFrom, fraction(nowMs, deadline))))
            }
        }
    }

    /** Playback paused, by anything but the timer: that ends a fade or a song being finished, not the countdown. */
    fun paused(): List<Effect> = when (phase) {
        Phase.FADING, Phase.FINISHING -> cancel()
        Phase.OFF, Phase.COUNTING -> emptyList()
    }

    /** Playback paused at the end of the song, as [Effect.ArmSongEnd] asked. */
    fun songEnded(): List<Effect> {
        if (phase == Phase.FINISHING) off()
        return emptyList()
    }

    /** When to tick next, or null when nothing is due. */
    fun nextTickMs(nowMs: Long): Long? {
        val deadline = deadlineMs ?: return null
        return when (phase) {
            Phase.OFF, Phase.FINISHING -> null
            Phase.COUNTING -> if (!finishTrack && nowMs < deadline - fadeMs) deadline - fadeMs else deadline
            Phase.FADING -> minOf(nowMs + FADE_STEP_MS, deadline)
        }
    }

    private fun fraction(nowMs: Long, deadlineMs: Long): Float =
        ((nowMs - (deadlineMs - fadeMs)).toFloat() / fadeMs).coerceIn(0f, 1f)

    private fun off() {
        phase = Phase.OFF
        deadlineMs = null
        finishTrack = false
    }

    companion object {
        /** About 0.25 dB a step over 10 s, below what the ear tells apart. */
        const val FADE_STEP_MS = 50L
    }
}
