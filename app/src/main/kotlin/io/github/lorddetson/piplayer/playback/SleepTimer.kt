package io.github.lorddetson.piplayer.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.lorddetson.piplayer.playback.SleepTimerMachine.Effect

/**
 * The sleep timer on the service's player (docs/playback.md §6.3): [SleepTimerMachine] decides, this applies its
 * decisions on the main thread and looks again when it says. The fade uses the player's own volume. Deadlines are in
 * elapsed realtime, which goes on in deep sleep; the handler's delays do not, so every tick checks the clock again.
 */
@OptIn(UnstableApi::class)
class SleepTimer(
    private val player: ExoPlayer,
    private val endOfSong: EndOfSongPause,
    private val onChange: () -> Unit,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    private var machine = SleepTimerMachine()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { tick() }

    val deadlineMs: Long? get() = machine.deadlineMs

    val finishTrack: Boolean get() = machine.finishTrack

    /** A timer of [durationMs], replacing a running one; 0 or less cancels. */
    fun start(durationMs: Long, finishTrack: Boolean) {
        apply(machine.cancel())
        machine = SleepTimerMachine(fadeMsOverride ?: FadeCurve.FADE_MS)
        apply(machine.start(now(), durationMs, finishTrack))
        tick()
    }

    fun release() {
        handler.removeCallbacks(tick)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            tick()
            return
        }
        apply(if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) machine.songEnded() else machine.paused())
        reschedule()
        onChange()
    }

    private fun tick() {
        val playing = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        apply(machine.tick(now(), playing, player.volume))
        reschedule()
        onChange()
    }

    private fun reschedule() {
        handler.removeCallbacks(tick)
        machine.nextTickMs(now())?.let { handler.postDelayed(tick, (it - now()).coerceAtLeast(0)) }
    }

    private fun apply(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                is Effect.SetVolume -> player.volume = effect.volume
                is Effect.PauseAndRestore -> {
                    player.pause()
                    player.volume = effect.volume
                }
                is Effect.RestoreVolume -> player.volume = effect.volume
                Effect.ArmSongEnd -> endOfSong.setForTimer(true)
                Effect.DisarmSongEnd -> endOfSong.setForTimer(false)
            }
        }
    }

    companion object {
        /** Replaces the 10 s fade in tests; read when a timer starts. */
        @VisibleForTesting
        @Volatile
        var fadeMsOverride: Long? = null
    }
}
