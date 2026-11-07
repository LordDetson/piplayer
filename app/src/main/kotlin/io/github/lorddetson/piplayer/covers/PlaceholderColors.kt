package io.github.lorddetson.piplayer.covers

/** The colors of a placeholder as ARGB: its [background] and the music [note] on it. */
data class PlaceholderColor(val background: Int, val note: Int)

/** Calm colors made from an album name (docs/library.md §13.4). */
object PlaceholderColors {

    /** The hue of [name], 0 to 360, the same on every device. */
    fun hue(name: String): Float = TODO()

    /** The colors of [name] in the dark theme ([dark]) or the light one. */
    fun of(name: String, dark: Boolean): PlaceholderColor = TODO()

    /** The contrast ratio of two opaque colors as WCAG defines it, 1 to 21. */
    fun contrast(a: Int, b: Int): Double = TODO()

    /** The relative luminance of an opaque color, 0 to 1. */
    fun luminance(color: Int): Double = TODO()
}
