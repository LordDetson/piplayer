package io.github.lorddetson.piplayer.ui.player

import io.github.lorddetson.piplayer.playback.PlaybackExtras

/**
 * Now Playing's second row (docs/playback.md §6.1): the state the service reports and what the buttons do.
 * [openEqualizer] is null when the device has no effect panel.
 */
data class ExtraControls(
    val extras: PlaybackExtras = PlaybackExtras.NONE,
    val setSleepTimer: (durationMs: Long, finishTrack: Boolean) -> Unit = { _, _ -> },
    val cancelSleepTimer: () -> Unit = {},
    val setStopAfterCurrent: (Boolean) -> Unit = {},
    val openEqualizer: (() -> Unit)? = null,
    /** The sleep timer sheet's last choice, and where a new one goes (§6.2). */
    val timerChoice: TimerChoice = TimerChoice.DEFAULT,
    val saveTimerChoice: (TimerChoice) -> Unit = {},
)
