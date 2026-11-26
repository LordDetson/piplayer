package io.github.lorddetson.piplayer.settings

import kotlinx.serialization.Serializable

@Serializable
internal data class UiGestureTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
) {
    val isValid: Boolean get() = translationX in -2f..2f && translationY in -2f..2f &&
        scale in 0.1f..3f && rotation in -360f..360f && alpha in 0f..1f &&
        pivotX in 0f..1f && pivotY in 0f..1f

    fun interpolate(end: UiGestureTransform, fraction: Float) = UiGestureTransform(
        translationX = mix(translationX, end.translationX, fraction),
        translationY = mix(translationY, end.translationY, fraction),
        scale = mix(scale, end.scale, fraction),
        rotation = mix(rotation, end.rotation, fraction),
        alpha = mix(alpha, end.alpha, fraction),
        pivotX = mix(pivotX, end.pivotX, fraction),
        pivotY = mix(pivotY, end.pivotY, fraction),
    )
}

@Serializable
internal data class UiGestureKeyframe(val progress: Float, val transform: UiGestureTransform = UiGestureTransform())

@Serializable
internal data class UiGestureTimeline(val target: UiGestureTarget, val keyframes: List<UiGestureKeyframe>) {
    val isValid: Boolean get() = target.isValid && keyframes.size in 2..12 &&
        keyframes.first().progress == 0f && keyframes.last().progress == 1f &&
        keyframes.all { it.progress in 0f..1f && it.transform.isValid } &&
        keyframes.zipWithNext().all { (before, after) -> before.progress < after.progress }

    fun at(progress: Float): UiGestureTransform {
        val first = keyframes.firstOrNull() ?: return UiGestureTransform()
        if (progress.isNaN() || progress <= first.progress) return first.transform
        val upper = keyframes.indexOfFirst { it.progress >= progress }
        if (upper == -1) return keyframes.last().transform
        val end = keyframes[upper]
        if (progress == end.progress) return end.transform
        val start = keyframes[upper - 1]
        return start.transform.interpolate(end.transform, (progress - start.progress) / (end.progress - start.progress))
    }
}

internal enum class UiGestureMotionPreset {
    SLIDE, CARDS, SCALE, FADE, NONE;

    fun timeline(target: UiGestureTarget): UiGestureTimeline {
        val end = when (this) {
            SLIDE -> UiGestureTransform(translationX = 1f)
            CARDS -> UiGestureTransform(translationX = 0.75f, scale = 0.86f, rotation = 8f, alpha = 0.82f)
            SCALE -> UiGestureTransform(scale = 0.78f)
            FADE -> UiGestureTransform(alpha = 0.2f)
            NONE -> UiGestureTransform()
        }
        return UiGestureTimeline(target, listOf(UiGestureKeyframe(0f), UiGestureKeyframe(1f, end)))
    }
}

private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress
