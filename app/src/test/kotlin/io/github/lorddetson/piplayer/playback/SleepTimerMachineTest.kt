package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.playback.SleepTimerMachine.Effect
import io.github.lorddetson.piplayer.playback.SleepTimerMachine.Phase
import kotlin.math.log10
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test

class SleepTimerMachineTest {

    private val minute = 60_000L
    private val machine = SleepTimerMachine(fadeMs = 10_000)

    private fun Effect.volume(): Float? = (this as? Effect.SetVolume)?.volume

    @Test
    fun `the fade falls in steps of the ear from the start volume to silence`() {
        val volumes = (0..10).map { FadeCurve.volume(0.8f, it / 10f) }

        assertThat(volumes.first()).isEqualTo(0.8f)
        assertThat(volumes.zipWithNext()).allSatisfy { (a, b) -> assertThat(b).isLessThan(a) }
        // Equal steps in decibels: halfway is half of -50 dB.
        assertThat(20 * log10(FadeCurve.volume(1f, 0.5f).toDouble())).isCloseTo(-25.0, offset(0.01))
        assertThat(FadeCurve.volume(0.8f, 1f)).isZero()
        assertThat(FadeCurve.volume(0.8f, 1.5f)).isZero()
        assertThat(FadeCurve.volume(0.8f, -0.5f)).isEqualTo(0.8f)
    }

    @Test
    fun `nothing happens before the fade, which ends with a pause at the deadline and the volume back`() {
        machine.start(nowMs = 0, durationMs = minute, finishTrack = false)

        assertThat(machine.phase).isEqualTo(Phase.COUNTING)
        assertThat(machine.deadlineMs).isEqualTo(minute)
        assertThat(machine.nextTickMs(0)).isEqualTo(minute - 10_000)
        assertThat(machine.tick(30_000, playing = true, volume = 1f)).isEmpty()

        val first = machine.tick(minute - 10_000, playing = true, volume = 0.9f)
        assertThat(machine.phase).isEqualTo(Phase.FADING)
        assertThat(first.single().volume()).isEqualTo(0.9f)
        assertThat(machine.nextTickMs(minute - 10_000)).isBetween(minute - 10_000 + 1, minute - 10_000 + 100)

        val middle = machine.tick(minute - 5_000, playing = true, volume = 0.5f)
        assertThat(middle.single().volume()).isCloseTo(FadeCurve.volume(0.9f, 0.5f), offset(1e-6f))

        val end = machine.tick(minute, playing = true, volume = 0.01f)
        assertThat(end).containsExactly(Effect.PauseAndRestore(0.9f))
        assertThat(machine.phase).isEqualTo(Phase.OFF)
        assertThat(machine.deadlineMs).isNull()
        assertThat(machine.nextTickMs(minute)).isNull()
    }

    @Test
    fun `a tick late from deep sleep past the deadline pauses and restores at once`() {
        machine.start(0, minute, finishTrack = false)

        assertThat(machine.tick(minute + 30_000, playing = true, volume = 1f)).containsExactly(Effect.PauseAndRestore(1f))
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `a pause during the fade ends the timer and restores the volume`() {
        machine.start(0, minute, finishTrack = false)
        machine.tick(minute - 10_000, playing = true, volume = 1f)
        machine.tick(minute - 4_000, playing = true, volume = 0.3f)

        assertThat(machine.paused()).containsExactly(Effect.RestoreVolume(1f))
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `a pause before the fade keeps counting in real time`() {
        machine.start(0, minute, finishTrack = false)

        assertThat(machine.paused()).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.COUNTING)
        // Paused through the fade's start: no fade while nothing plays.
        assertThat(machine.tick(minute - 5_000, playing = false, volume = 1f)).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.COUNTING)
    }

    @Test
    fun `a timer that is up while nothing plays just ends`() {
        machine.start(0, minute, finishTrack = false)

        assertThat(machine.tick(minute, playing = false, volume = 1f)).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `playback resumed within the fade window fades from where the fade would be`() {
        machine.start(0, minute, finishTrack = false)

        val effects = machine.tick(minute - 2_000, playing = true, volume = 1f)

        assertThat(machine.phase).isEqualTo(Phase.FADING)
        assertThat(effects.single().volume()).isCloseTo(FadeCurve.volume(1f, 0.8f), offset(1e-6f))
    }

    @Test
    fun `cancel and a new timer restore the volume of a fade`() {
        machine.start(0, minute, finishTrack = false)
        machine.tick(minute - 10_000, playing = true, volume = 0.5f)

        assertThat(machine.start(minute - 5_000, 2 * minute, finishTrack = false)).containsExactly(Effect.RestoreVolume(0.5f))
        assertThat(machine.phase).isEqualTo(Phase.COUNTING)
        assertThat(machine.deadlineMs).isEqualTo(minute - 5_000 + 2 * minute)

        machine.tick(machine.deadlineMs!! - 10_000, playing = true, volume = 0.5f)
        assertThat(machine.cancel()).containsExactly(Effect.RestoreVolume(0.5f))
        assertThat(machine.phase).isEqualTo(Phase.OFF)
        assertThat(machine.cancel()).isEmpty()
    }

    @Test
    fun `a duration of zero or less only cancels`() {
        machine.start(0, minute, finishTrack = false)

        assertThat(machine.start(1_000, 0, finishTrack = false)).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `finish-track mode waits for the end of the song without a fade`() {
        machine.start(0, minute, finishTrack = true)

        assertThat(machine.nextTickMs(0)).isEqualTo(minute)
        assertThat(machine.tick(minute - 5_000, playing = true, volume = 1f)).isEmpty()
        assertThat(machine.tick(minute, playing = true, volume = 1f)).containsExactly(Effect.ArmSongEnd)
        assertThat(machine.phase).isEqualTo(Phase.FINISHING)
        assertThat(machine.nextTickMs(minute)).isNull()

        assertThat(machine.songEnded()).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `a pause while the song is being finished ends the timer`() {
        machine.start(0, minute, finishTrack = true)
        machine.tick(minute, playing = true, volume = 1f)

        assertThat(machine.paused()).containsExactly(Effect.DisarmSongEnd)
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }

    @Test
    fun `cancel while the song is being finished disarms the pause`() {
        machine.start(0, minute, finishTrack = true)
        machine.tick(minute, playing = true, volume = 1f)

        assertThat(machine.cancel()).containsExactly(Effect.DisarmSongEnd)
    }

    @Test
    fun `finish-track mode that is up while nothing plays just ends`() {
        machine.start(0, minute, finishTrack = true)

        assertThat(machine.tick(minute + 1, playing = false, volume = 1f)).isEmpty()
        assertThat(machine.phase).isEqualTo(Phase.OFF)
    }
}
