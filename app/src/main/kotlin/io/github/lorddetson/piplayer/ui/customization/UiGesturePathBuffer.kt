package io.github.lorddetson.piplayer.ui.customization

import io.github.lorddetson.piplayer.settings.UiGesturePoint

/** Keep progressively spaced interior samples without losing either end of a long stroke. */
internal class UiGesturePathBuffer {
    private val samples = mutableListOf<UiGesturePoint>()
    private var count = 0L
    private var spacing = 1L
    private var last: UiGesturePoint? = null

    fun add(point: UiGesturePoint) {
        if (last == point) return
        if (count % spacing == 0L) {
            samples += point
            if (samples.size >= 512) {
                val spaced = samples.filterIndexed { index, _ -> index % 2 == 0 }
                samples.clear()
                samples.addAll(spaced)
                spacing *= 2
            }
        }
        count++
        last = point
    }

    fun points(): List<UiGesturePoint> = last?.takeUnless { it == samples.lastOrNull() }?.let { samples + it }
        ?: samples.toList()
}
