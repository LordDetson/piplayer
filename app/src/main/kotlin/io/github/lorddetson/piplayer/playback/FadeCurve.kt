package io.github.lorddetson.piplayer.playback

import kotlin.math.pow

/** The sleep timer's fade (docs/playback.md §6.3). */
object FadeCurve {

    /** How long the volume falls before the timer is up. */
    const val FADE_MS = 10_000L

    /** Where the fade ends, below the starting volume, just before the pause. */
    const val FLOOR_DB = -50f

    /**
     * The volume at [fraction] (0 to 1) of the fade from [start]: equal steps in decibels, which the ear hears as an
     * even fall, and silence at the end.
     */
    fun volume(start: Float, fraction: Float): Float = when {
        fraction <= 0f -> start
        fraction >= 1f -> 0f
        else -> start * 10f.pow(FLOOR_DB * fraction / 20f)
    }
}
