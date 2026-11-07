package io.github.lorddetson.piplayer.covers

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** The colors of a placeholder as ARGB: its [background] and the music [note] on it. */
data class PlaceholderColor(val background: Int, val note: Int)

/** Calm colors made from an album name (docs/library.md §13.4). */
object PlaceholderColors {

    private const val FNV_OFFSET = 0x811C9DC5L
    private const val FNV_PRIME = 0x01000193L

    /** The hue of [name], 0 to 360, the same on every device: FNV-1a (32 bit) of the lowercase name's UTF-8. */
    fun hue(name: String): Float {
        var hash = FNV_OFFSET
        for (byte in name.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)) {
            hash = ((hash xor (byte.toLong() and 0xFF)) * FNV_PRIME) and 0xFFFFFFFFL
        }
        return (hash % 360).toFloat()
    }

    /** The colors of [name] in the dark theme ([dark]) or the light one. */
    fun of(name: String, dark: Boolean): PlaceholderColor {
        val hue = hue(name)
        return if (dark) {
            PlaceholderColor(hsl(hue, 0.25f, 0.22f), hsl(hue, 0.35f, 0.78f))
        } else {
            PlaceholderColor(hsl(hue, 0.35f, 0.86f), hsl(hue, 0.35f, 0.30f))
        }
    }

    /** The contrast ratio of two opaque colors as WCAG defines it, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The relative luminance of an opaque color, 0 to 1. */
    fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color shr 16 and 0xFF) + 0.7152 * linear(color shr 8 and 0xFF) + 0.0722 * linear(color and 0xFF)
    }

    /** An opaque ARGB color from hue (degrees), saturation and lightness. */
    private fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
        val chroma = (1 - abs(2 * lightness - 1)) * saturation
        val section = hue / 60f
        val x = chroma * (1 - abs(section % 2 - 1))
        val (r, g, b) = when (section.toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = lightness - chroma / 2
        fun channel(value: Float) = ((value + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
