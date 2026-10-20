package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player

/** The repeat modes in the order a tap goes through them, on Now Playing and in the notification (docs/playback.md §3.6). */
object RepeatModes {

    val CYCLE: List<Int> = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)

    /** The mode after [mode]. */
    fun next(mode: Int): Int = CYCLE[(CYCLE.indexOf(mode) + 1) % CYCLE.size]
}
