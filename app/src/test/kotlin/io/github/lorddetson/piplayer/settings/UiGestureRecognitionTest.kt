package io.github.lorddetson.piplayer.settings

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UiGestureRecognitionTest {
    private val tap = builtIn(UiBuiltInGesture.TAP)
    private val doubleTap = builtIn(UiBuiltInGesture.DOUBLE_TAP)
    private val tripleTap = builtIn(UiBuiltInGesture.TRIPLE_TAP)
    private val hold = builtIn(UiBuiltInGesture.LONG_PRESS)
    private val tapHold = builtIn(UiBuiltInGesture.TAP_THEN_LONG_PRESS)
    private val doubleHold = builtIn(UiBuiltInGesture.DOUBLE_TAP_THEN_LONG_PRESS)

    @Test
    fun `stock taps and holds dispatch immediately with no active longer prefix`() {
        val recognizer = UiGestureTapRecognizer(setOf(tap, hold), 250)
        assertThat(recognizer.pressStarted(0)).isEmpty()
        assertThat(recognizer.shortReleased(35)).containsExactly(tap)
        assertThat(recognizer.deadlineMillis).isNull()
        assertThat(recognizer.pressStarted(70)).isEmpty()
        assertThat(recognizer.longPressed(570)).containsExactly(hold)
        assertThat(recognizer.advance(1000)).isEmpty()
    }

    @Test
    fun `only the longest matching tap prefix commits before or at the exact deadline`() {
        val recognizer = UiGestureTapRecognizer(setOf(tap, doubleTap, tripleTap), 250)
        assertThat(recognizer.shortReleased(10)).isEmpty()
        assertThat(recognizer.deadlineMillis).isEqualTo(260)
        assertThat(recognizer.advance(259)).isEmpty()
        assertThat(recognizer.advance(260)).containsExactly(tap)
        assertThat(recognizer.shortReleased(500)).isEmpty()
        assertThat(recognizer.shortReleased(600)).isEmpty()
        assertThat(recognizer.advance(850)).containsExactly(doubleTap)
        assertThat(recognizer.shortReleased(900)).isEmpty()
        assertThat(recognizer.shortReleased(1000)).isEmpty()
        assertThat(recognizer.shortReleased(1100)).containsExactly(tripleTap)
        assertThat(recognizer.advance(1400)).isEmpty()
    }

    @Test
    fun `a timely next press holds the prefix through the long threshold`() {
        val recognizer = UiGestureTapRecognizer(setOf(tap, doubleTap, tapHold, doubleHold, hold), 250)
        assertThat(recognizer.shortReleased(10)).isEmpty()
        assertThat(recognizer.pressStarted(150)).isEmpty()
        assertThat(recognizer.deadlineMillis).isNull()
        assertThat(recognizer.advance(450)).isEmpty()
        assertThat(recognizer.longPressed(650)).containsExactly(tapHold)
        assertThat(recognizer.shortReleased(700)).isEmpty()
        assertThat(recognizer.shortReleased(810)).isEmpty()
        assertThat(recognizer.pressStarted(900)).isEmpty()
        assertThat(recognizer.longPressed(1400)).containsExactly(doubleHold)
        assertThat(recognizer.advance(2000)).isEmpty()
    }

    @Test
    fun `unsupported extensions dispatch the last completed token and cancellation clears pending input`() {
        val recognizer = UiGestureTapRecognizer(setOf(tap, tapHold, hold), 250)
        assertThat(recognizer.shortReleased(40)).isEmpty()
        assertThat(recognizer.pressStarted(60)).isEmpty()
        assertThat(recognizer.shortReleased(80)).containsExactly(tap)
        assertThat(recognizer.deadlineMillis).isEqualTo(330)
        recognizer.cancel()
        assertThat(recognizer.advance(330)).isEmpty()
        val sparse = UiGestureTapRecognizer(setOf(tripleTap, hold), 250)
        assertThat(sparse.shortReleased(400)).isEmpty()
        assertThat(sparse.pressStarted(650)).isEmpty()
        assertThat(sparse.longPressed(1150)).containsExactly(hold)
    }

    @Test
    fun `all cardinal swipes preview bounded monotonic distance and short or diagonal paths cancel`() {
        val swipes = mapOf(
            UiBuiltInGesture.SWIPE_LEFT to UiGesturePoint(-150f, 0f),
            UiBuiltInGesture.SWIPE_RIGHT to UiGesturePoint(150f, 0f),
            UiBuiltInGesture.SWIPE_UP to UiGesturePoint(0f, -150f),
            UiBuiltInGesture.SWIPE_DOWN to UiGesturePoint(0f, 150f),
        )
        val recognizer = motion(swipes.keys.map(::builtIn).toSet())
        for ((gesture, end) in swipes) {
            val halfWay = UiGesturePoint(end.x / 3, end.y / 3)
            assertThat(recognizer.preview(listOf(origin, halfWay))).isEqualTo(UiGestureMatch(builtIn(gesture), 0.5f))
            assertThat(recognizer.finish(listOf(origin, end))).isEqualTo(UiGestureMatch(builtIn(gesture), 1f))
        }
        val partial = listOf(origin, UiGesturePoint(15f, 0f))
        assertThat(recognizer.preview(partial)?.gesture).isEqualTo(builtIn(UiBuiltInGesture.SWIPE_RIGHT))
        assertThat(recognizer.finish(partial)).isNull()
        assertThat(recognizer.finish(listOf(origin, UiGesturePoint(100f, 90f)))).isNull()
        assertThat(recognizer.finish(listOf(origin, UiGesturePoint(100f, 0f), origin))).isNull()
    }

    @Test
    fun `a loop has signed winding and partial loops preview without committing`() {
        val clockwise = builtIn(UiBuiltInGesture.CIRCLE_CLOCKWISE)
        val counterclockwise = builtIn(UiBuiltInGesture.CIRCLE_COUNTERCLOCKWISE)
        val right = builtIn(UiBuiltInGesture.SWIPE_RIGHT)
        val down = builtIn(UiBuiltInGesture.SWIPE_DOWN)
        val recognizer = motion(setOf(clockwise, counterclockwise, right, down))
        val loop = (0..32).map { index ->
            val angle = 2 * PI * index / 32
            UiGesturePoint(50f * cos(angle).toFloat() + 60f, 50f * sin(angle).toFloat() + 80f)
        }
        assertThat(recognizer.finish(loop)).isEqualTo(UiGestureMatch(clockwise, 1f))
        assertThat(recognizer.finish(loop.reversed())).isEqualTo(UiGestureMatch(counterclockwise, 1f))
        assertThat(recognizer.preview(loop.take(24))?.gesture).isEqualTo(clockwise)
        assertThat(recognizer.finish(loop.take(24))).isNull()
        assertThat(recognizer.finish(loop.take(17) + loop.take(17).asReversed())).isNull()
        assertThat(recognizer.finish(loop.map { it.copy(y = it.y / 7) })).isNull()
    }

    @Test
    fun `a recorded path matches with different sample density scale and origin but rejects ambiguity`() {
        val points = listOf(origin, UiGesturePoint(60f, 90f), UiGesturePoint(120f, 0f))
        val preset = checkNotNull(UiGesturePreset.record("chevron", "Chevron", points))
        assertThat(preset.points.size).isBetween(12, 48)
        assertThat(preset.points).allMatch { it.x in 0f..1f && it.y in 0f..1f }
        assertThat(UiGesturePreset.record("empty", "Empty", listOf(origin, origin))).isNull()
        val attempt = listOf(origin, UiGesturePoint(15f, 22.5f), points[1], UiGesturePoint(102f, 27f), points[2])
            .map { UiGesturePoint(it.x * 1.7f + 150, it.y * 1.7f - 40) }
        val custom = UiGesture.custom(preset.id)
        val swipe = builtIn(UiBuiltInGesture.SWIPE_RIGHT)
        val recognizer = motion(setOf(custom, swipe), listOf(preset))
        assertThat(recognizer.finish(attempt)?.gesture).isEqualTo(custom)
        assertThat(recognizer.finish(attempt.reversed())).isNull()
        assertThat(recognizer.finish(listOf(origin, UiGesturePoint(120f, 0f)))?.gesture).isEqualTo(swipe)
        val duplicate = preset.copy(id = "duplicate")
        val ambiguous = motion(setOf(custom, UiGesture.custom(duplicate.id), swipe), listOf(preset, duplicate))
        assertThat(ambiguous.finish(attempt)).isNull()
    }

    @Test
    fun `a custom path previews only after a distinct geometric prefix`() {
        val points = listOf(origin, UiGesturePoint(60f, 90f), UiGesturePoint(120f, 0f))
        val preset = checkNotNull(UiGesturePreset.record("shape", "Chevron", points))
        val gesture = UiGesture.custom(preset.id)
        val recognizer = motion(setOf(gesture), listOf(preset))
        val preview = recognizer.preview(points.take(2) + UiGesturePoint(83f, 56f))
        assertThat(preview?.gesture).isEqualTo(gesture)
        assertThat(preview?.progress).isBetween(0.5f, 0.9f)
        assertThat(recognizer.finish(points.take(2) + UiGesturePoint(83f, 56f))).isNull()
    }

    @Test
    fun `a recorded line progresses when its physical component has no competing swipe`() {
        val points = listOf(origin, UiGesturePoint(100f, 0f))
        val preset = checkNotNull(UiGesturePreset.record("line", "Line", points))
        val gesture = UiGesture.custom(preset.id)
        val recognizer = motion(setOf(gesture), listOf(preset))
        assertThat(recognizer.preview(listOf(origin, UiGesturePoint(50f, 0f))))
            .isEqualTo(UiGestureMatch(gesture, 0.5f))
        assertThat(recognizer.finish(listOf(origin, UiGesturePoint(50f, 0f))))
            .isEqualTo(UiGestureMatch(gesture, 1f))
    }

    private val origin = UiGesturePoint(0f, 0f)

    private fun builtIn(gesture: UiBuiltInGesture) = UiGesture.builtIn(gesture)

    private fun motion(enabled: Set<UiGesture>, presets: List<UiGesturePreset> = emptyList()) =
        UiGestureMotionRecognizer(enabled, presets, minimumDistance = 25f, fullProgressDistance = 100f)
}
