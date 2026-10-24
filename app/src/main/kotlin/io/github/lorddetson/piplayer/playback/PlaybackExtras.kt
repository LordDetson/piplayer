package io.github.lorddetson.piplayer.playback

import android.os.Bundle

/**
 * The sleep timer and "stop after current track" as the service tells PiPlayer's own controllers (docs/playback.md
 * §6.5). [sleepDeadlineMs] is in [android.os.SystemClock.elapsedRealtime], which the screen shares with the service.
 */
data class PlaybackExtras(
    val sleepDeadlineMs: Long? = null,
    val finishTrack: Boolean = false,
    val stopAfterCurrent: Boolean = false,
) {
    /** The time left on the timer at [nowMs], or null without one. */
    fun sleepLeftMs(nowMs: Long): Long? = sleepDeadlineMs?.let { (it - nowMs).coerceAtLeast(0) }

    fun toBundle(): Bundle = Bundle().apply {
        sleepDeadlineMs?.let { putLong(KEY_SLEEP_DEADLINE_MS, it) }
        putBoolean(KEY_FINISH_TRACK, finishTrack)
        putBoolean(KEY_STOP_AFTER_CURRENT, stopAfterCurrent)
    }

    companion object {
        val NONE = PlaybackExtras()

        private const val KEY_SLEEP_DEADLINE_MS = "io.github.lorddetson.piplayer.playback.SLEEP_DEADLINE_MS"
        private const val KEY_FINISH_TRACK = "io.github.lorddetson.piplayer.playback.SLEEP_FINISH_TRACK"
        private const val KEY_STOP_AFTER_CURRENT = "io.github.lorddetson.piplayer.playback.STOP_AFTER_CURRENT"

        fun from(bundle: Bundle): PlaybackExtras = PlaybackExtras(
            sleepDeadlineMs = if (bundle.containsKey(KEY_SLEEP_DEADLINE_MS)) bundle.getLong(KEY_SLEEP_DEADLINE_MS) else null,
            finishTrack = bundle.getBoolean(KEY_FINISH_TRACK),
            stopAfterCurrent = bundle.getBoolean(KEY_STOP_AFTER_CURRENT),
        )
    }
}
