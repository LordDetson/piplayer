package io.github.lorddetson.piplayer.ui.customization

import io.github.lorddetson.piplayer.settings.UiGesturePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiGesturePathBufferTest {
    @Test
    fun longStrokeRetainsDownUpAndSamplesFromTheWholePath() {
        val buffer = UiGesturePathBuffer()
        val points = List(5_001) { UiGesturePoint(it.toFloat(), (it % 180).toFloat()) }
        points.forEach(buffer::add)
        val stored = buffer.points()
        assertEquals(points.first(), stored.first())
        assertEquals(points.last(), stored.last())
        assertTrue(stored.size <= 512)
        assertTrue(stored.zipWithNext().all { (start, end) -> end.x - start.x in 1f..32f })
    }
}
