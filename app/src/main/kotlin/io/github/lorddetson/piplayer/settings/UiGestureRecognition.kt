package io.github.lorddetson.piplayer.settings

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

private const val PATH_SAMPLES = 32
private const val MAX_INPUT_POINTS = 4096
private const val CUSTOM_MATCH_DISTANCE = 0.105
private const val CUSTOM_AMBIGUITY_MARGIN = 0.035
private const val FIRST_PREVIEW_SAMPLE = 11
private const val MIN_SEGMENT = 0.00001f

@Serializable
internal data class UiGesturePoint(val x: Float, val y: Float) {
    internal fun distance(other: UiGesturePoint) = hypot(other.x - x, other.y - y)
}

@Serializable
internal data class UiGesturePreset(val id: String, val name: String, val points: List<UiGesturePoint>) {
    val isValid: Boolean get() = validGesturePresetId(id) && name.isNotBlank() && name.length <= 64 &&
        name.none(Char::isISOControl) && points.size == PATH_SAMPLES &&
        points.all { it.x in 0f..1f && it.y in 0f..1f } &&
        (GesturePath.read(points)?.extent ?: 0f) >= 0.95f

    companion object {
        fun record(id: String, name: String, points: List<UiGesturePoint>): UiGesturePreset? {
            val path = GesturePath.read(points)?.takeIf { it.extent >= 1f } ?: return null
            return UiGesturePreset(id, name.trim(), path.normalized()).takeIf(UiGesturePreset::isValid)
        }
    }
}

internal data class UiGestureMatch(val gesture: UiGesture, val progress: Float)

internal class UiGestureMotionRecognizer(
    private val enabled: Set<UiGesture>,
    presets: List<UiGesturePreset>,
    private val minimumDistance: Float,
    private val fullProgressDistance: Float,
) {
    private val recordings = presets.filter { it.isValid && UiGesture.custom(it.id) in enabled }.map(::Recording)

    init {
        require(minimumDistance.isFinite() && minimumDistance > 0f)
        require(fullProgressDistance.isFinite() && fullProgressDistance >= minimumDistance)
    }

    fun preview(points: List<UiGesturePoint>): UiGestureMatch? = recognize(points, complete = false)

    fun finish(points: List<UiGesturePoint>): UiGestureMatch? = recognize(points, complete = true)

    private fun recognize(points: List<UiGesturePoint>, complete: Boolean): UiGestureMatch? {
        val path = GesturePath.read(points) ?: return null
        val minimum = if (complete) minimumDistance else minimumDistance * 0.35f
        if (path.length < minimum) return null
        if (recordings.isNotEmpty() && path.extent >= minimum) {
            val input = path.normalized()
            val distanceProgress = (path.length / fullProgressDistance).coerceAtMost(1f)
            val candidates = recordings.mapNotNull { it.match(input, complete, distanceProgress) }.sortedBy(RecordedMatch::score)
            val first = candidates.firstOrNull()
            if (first != null) {
                val next = candidates.getOrNull(1)
                if (next != null && next.score - first.score < CUSTOM_AMBIGUITY_MARGIN) return null
                return first.match
            }
        }
        path.circle(minimum, complete)?.takeIf { it.gesture in enabled }?.let { return it }
        return path.swipe(minimum, fullProgressDistance)?.takeIf { it.gesture in enabled }
    }

    private class Recording(private val preset: UiGesturePreset) {
        private val straight = (GesturePath.read(preset.points)?.directness ?: 0f) > 0.95f
        private val prefixes = if (straight) emptyList() else (FIRST_PREVIEW_SAMPLE until preset.points.size).mapNotNull { lastIndex ->
            val path = GesturePath.read(preset.points.take(lastIndex + 1)) ?: return@mapNotNull null
            if (path.directness > 0.95f) return@mapNotNull null
            val progress = lastIndex.toFloat() / (preset.points.size - 1)
            progress to path.normalized()
        }

        fun match(input: List<UiGesturePoint>, complete: Boolean, distanceProgress: Float): RecordedMatch? {
            // Normalizing a straight prefix loses its length, so use physical movement for its preview.
            val candidates = when {
                complete -> listOf(1f to preset.points)
                straight -> listOf(distanceProgress to preset.points)
                else -> prefixes
            }
            return candidates.mapNotNull { (progress, expected) ->
                val score = matchDistance(input, expected)
                if (score <= CUSTOM_MATCH_DISTANCE) {
                    RecordedMatch(UiGestureMatch(UiGesture.custom(preset.id), progress), score)
                } else null
            }.minByOrNull(RecordedMatch::score)
        }
    }

    private data class RecordedMatch(val match: UiGestureMatch, val score: Double)
}

/** Avoid binding indistinguishable movements to different slots on one physical component. */
internal fun UiGesturePreset.conflictsWith(other: UiGesture, presets: List<UiGesturePreset>): Boolean {
    val otherPreset = presets.firstOrNull { it.id == other.presetId }
    if (otherPreset != null) return matchDistance(points, otherPreset.points) <= CUSTOM_MATCH_DISTANCE
    val path = GesturePath.read(points) ?: return false
    return path.circle(0.25f, complete = true)?.gesture == other || path.swipe(0.25f, 1f)?.gesture == other
}

private fun matchDistance(input: List<UiGesturePoint>, expected: List<UiGesturePoint>): Double {
    val meanSquare = input.indices.sumOf { index ->
        val distance = input[index].distance(expected[index]).toDouble()
        distance * distance
    } / input.size
    val endpoints = input.first().distance(expected.first()) + input.last().distance(expected.last())
    return sqrt(meanSquare) * 0.8 + endpoints * 0.1
}

private class GesturePath private constructor(
    private val points: List<UiGesturePoint>,
    private val distances: List<Float>,
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
) {
    val length: Float get() = distances.last()
    val extent: Float get() = maxOf(width, height)
    private val displacement: Float get() = points.first().distance(points.last())
    val directness: Float get() = displacement / length

    fun normalized(): List<UiGesturePoint> {
        var segment = 1
        return List(PATH_SAMPLES) { index ->
            val distance = if (index == PATH_SAMPLES - 1) length else length * (index.toFloat() / (PATH_SAMPLES - 1))
            while (segment < distances.lastIndex && distances[segment] < distance) segment++
            val start = points[segment - 1]
            val end = points[segment]
            val fraction = (distance - distances[segment - 1]) / (distances[segment] - distances[segment - 1])
            UiGesturePoint(
                ((start.x - left + (end.x - start.x) * fraction) / extent).coerceIn(0f, 1f),
                ((start.y - top + (end.y - start.y) * fraction) / extent).coerceIn(0f, 1f),
            )
        }
    }

    fun swipe(minimumDistance: Float, fullProgressDistance: Float): UiGestureMatch? {
        if (directness < 0.83f) return null
        val dx = points.last().x - points.first().x
        val dy = points.last().y - points.first().y
        val main = maxOf(abs(dx), abs(dy))
        if (main < minimumDistance || main < minOf(abs(dx), abs(dy)) * 1.45f) return null
        val gesture = when {
            abs(dx) > abs(dy) -> if (dx > 0f) UiBuiltInGesture.SWIPE_RIGHT else UiBuiltInGesture.SWIPE_LEFT
            dy > 0f -> UiBuiltInGesture.SWIPE_DOWN
            else -> UiBuiltInGesture.SWIPE_UP
        }
        return UiGestureMatch(UiGesture.builtIn(gesture), (main / fullProgressDistance).coerceAtMost(1f))
    }

    fun circle(minimumDistance: Float, complete: Boolean): UiGestureMatch? {
        if (extent < minimumDistance || minOf(width, height) < extent * 0.55f || points.size < 6) return null
        val centerX = left + width / 2
        val centerY = top + height / 2
        val angles = points.map { atan2((it.y - centerY).toDouble(), (it.x - centerX).toDouble()) }
        val deltas = angles.zipWithNext { before, after ->
            var delta = after - before
            if (delta > PI) delta -= 2 * PI
            if (delta < -PI) delta += 2 * PI
            delta
        }
        val winding = deltas.sum()
        val rotation = abs(winding)
        if (rotation < PI || rotation > 2.5 * PI || rotation < deltas.sumOf(::abs) * 0.82) return null
        if (points.any { hypot(it.x - centerX, it.y - centerY) < extent * 0.16f }) return null
        if (complete && (rotation < 1.65 * PI || displacement > extent * 0.28f)) return null
        val gesture = if (winding > 0) UiBuiltInGesture.CIRCLE_CLOCKWISE else UiBuiltInGesture.CIRCLE_COUNTERCLOCKWISE
        val progress = if (complete) 1f else (rotation / (2 * PI)).toFloat().coerceAtMost(1f)
        return UiGestureMatch(UiGesture.builtIn(gesture), progress)
    }

    companion object {
        fun read(input: List<UiGesturePoint>): GesturePath? {
            if (input.size !in 2..MAX_INPUT_POINTS ||
                input.any { it.x !in -1_000_000f..1_000_000f || it.y !in -1_000_000f..1_000_000f }
            ) return null
            val points = mutableListOf(input.first())
            val distances = mutableListOf(0f)
            for (point in input.drop(1)) {
                val segment = point.distance(points.last())
                val distance = distances.last() + segment
                if (segment >= MIN_SEGMENT && distance > distances.last()) {
                    points += point
                    distances += distance
                }
            }
            if (points.size < 2) return null
            val left = points.minOf(UiGesturePoint::x)
            val top = points.minOf(UiGesturePoint::y)
            return GesturePath(points, distances, left, top, points.maxOf(UiGesturePoint::x) - left,
                points.maxOf(UiGesturePoint::y) - top)
        }
    }
}
