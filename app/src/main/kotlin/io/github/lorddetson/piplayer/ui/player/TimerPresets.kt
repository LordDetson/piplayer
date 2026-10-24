package io.github.lorddetson.piplayer.ui.player

/** The sleep timer's ready-made times (docs/playback.md §6.2). */
object TimerPresets {

    /** In minutes, from the longest to the shortest. */
    val MINUTES: List<Int> = listOf(90, 60, 45, 30, 15)

    /** The preset that [hours] and [minutes] make, if any. */
    fun matching(hours: Int, minutes: Int): Int? = (hours * 60 + minutes).takeIf { it in MINUTES }
}
