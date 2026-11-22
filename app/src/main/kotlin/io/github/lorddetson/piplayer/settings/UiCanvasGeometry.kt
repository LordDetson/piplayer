package io.github.lorddetson.piplayer.settings

import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class UiResizeEdge(val horizontal: Int, val vertical: Int) {
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1), LEFT(-1, 0), RIGHT(1, 0),
    BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1),
}

private const val ALIGNMENT_TOLERANCE = 9

internal val UiCanvasBounds.right: Int get() = x + width
internal val UiCanvasBounds.bottom: Int get() = y + height

internal fun List<UiCanvasBounds>.canvasBounds(): UiCanvasBounds {
    val left = minOf(UiCanvasBounds::x)
    val top = minOf(UiCanvasBounds::y)
    return UiCanvasBounds(left, top, maxOf(UiCanvasBounds::right) - left, maxOf(UiCanvasBounds::bottom) - top)
}

internal fun UiCanvasBounds.resize(edge: UiResizeEdge, dx: Int, dy: Int): UiCanvasBounds {
    val left = if (edge.horizontal < 0) x + dx.coerceIn(-x, width - UI_CANVAS_MIN_SIZE) else x
    val top = if (edge.vertical < 0) y + dy.coerceIn(-y, height - UI_CANVAS_MIN_SIZE) else y
    val newRight = if (edge.horizontal > 0) right + dx.coerceIn(UI_CANVAS_MIN_SIZE - width, UI_CANVAS_UNITS - right) else right
    val newBottom = if (edge.vertical > 0) bottom + dy.coerceIn(UI_CANVAS_MIN_SIZE - height, UI_CANVAS_UNITS - bottom) else bottom
    return UiCanvasBounds(left, top, newRight - left, newBottom - top)
}

internal fun UiCanvasBounds.scaleWithin(from: UiCanvasBounds, to: UiCanvasBounds): UiCanvasBounds {
    fun xAt(edge: Int) = to.x + ((edge - from.x) * to.width.toDouble() / from.width).roundToInt()
    fun yAt(edge: Int) = to.y + ((edge - from.y) * to.height.toDouble() / from.height).roundToInt()
    val left = xAt(x)
    val top = yAt(y)
    return UiCanvasBounds(left, top, xAt(right) - left, yAt(bottom) - top)
}

internal fun alignmentOffset(edges: List<Int>, guides: List<Int>, allowed: IntRange): Int =
    edges.flatMap { edge -> guides.map { it - edge } }.asSequence()
        .filter { abs(it) <= ALIGNMENT_TOLERANCE && it in allowed }.minByOrNull(::abs) ?: 0

internal fun resizeAlignmentOffset(direction: Int, guides: List<Int>, start: Int, end: Int): Int = when {
    direction < 0 -> alignmentOffset(listOf(start), guides, -start..(end - start - UI_CANVAS_MIN_SIZE))
    direction > 0 -> alignmentOffset(listOf(end), guides, (UI_CANVAS_MIN_SIZE - (end - start))..(UI_CANVAS_UNITS - end))
    else -> 0
}

private fun UiCanvasBounds.overlaps(other: UiCanvasBounds) =
    x < other.right && right > other.x && y < other.bottom && bottom > other.y

internal fun List<UiCanvasItem>.hasOverlappingBounds(): Boolean = indices.any { index ->
    (index + 1 until size).any { other -> this[index].bounds.overlaps(this[other].bounds) }
}

/** Move the contacted clusters together. Each attempted chain advances in a single direction. */
internal fun pushCanvasItems(
    items: List<UiCanvasItem>,
    groups: List<Set<String>>,
    selected: Set<String>,
    dx: Int,
    dy: Int,
): List<UiCanvasItem>? {
    val memberships = groups.flatMap { group -> group.map { it to group } }.toMap()
    val clusters = items.groupBy { memberships[it.id] ?: setOf(it.id) }
        .map { (members, visible) -> CanvasCluster(visible, visible.size == members.size) }
    if (clusters.any { it.items.hasOverlappingBounds() }) return null

    val active = clusters.indexOfFirst { cluster -> cluster.items.any { it.id in selected } }
    val resolved = if (active >= 0) {
        PushDirection.preferred(dx, dy).firstNotNullOfOrNull { clusters.pushFrom(active, it, fixed = setOf(active)) }
    } else {
        clusters.resolveExistingOverlaps() ?: clusters.asReversed().resolveExistingOverlaps()
    } ?: return null
    val displaced = resolved.flatMap(CanvasCluster::items).associateBy(UiCanvasItem::id)
    return items.map { displaced.getValue(it.id) }.takeUnless { it.hasOverlappingBounds() }
}

private data class CanvasCluster(val items: List<UiCanvasItem>, val complete: Boolean) {
    fun move(direction: PushDirection, distance: Int): CanvasCluster? {
        if (!complete) return null
        val moved = items.map { item ->
            val bounds = item.bounds
            item.copy(bounds = bounds.copy(x = bounds.x + direction.x * distance, y = bounds.y + direction.y * distance))
        }
        return if (moved.all { it.bounds.isValid }) copy(items = moved) else null
    }

    fun overlaps(other: CanvasCluster) = items.any { item -> other.items.any { item.bounds.overlaps(it.bounds) } }

    fun push(other: CanvasCluster, direction: PushDirection): CanvasCluster? {
        var displaced = other
        while (true) {
            val required = items.maxOf { origin -> displaced.items.maxOf { neighbor ->
                if (origin.bounds.overlaps(neighbor.bounds)) direction.distance(origin.bounds, neighbor.bounds) else 0
            } }
            if (required == 0) return displaced
            displaced = displaced.move(direction, required) ?: return null
        }
    }
}

private enum class PushDirection(val x: Int, val y: Int) {
    RIGHT(1, 0), LEFT(-1, 0), DOWN(0, 1), UP(0, -1);

    fun distance(from: UiCanvasBounds, neighbor: UiCanvasBounds): Int = when (this) {
        RIGHT -> from.right - neighbor.x
        LEFT -> neighbor.right - from.x
        DOWN -> from.bottom - neighbor.y
        UP -> neighbor.bottom - from.y
    }

    companion object {
        fun preferred(dx: Int, dy: Int): List<PushDirection> {
            val horizontal = if (dx >= 0) listOf(RIGHT, LEFT) else listOf(LEFT, RIGHT)
            val vertical = if (dy >= 0) listOf(DOWN, UP) else listOf(UP, DOWN)
            val (primary, secondary) = if (abs(dx) >= abs(dy)) horizontal to vertical else vertical to horizontal
            return listOf(primary.first(), secondary.first(), secondary.last(), primary.last())
        }
    }
}

private fun List<CanvasCluster>.resolveExistingOverlaps(): List<CanvasCluster>? {
    var placed = this
    for (anchor in indices) {
        if ((anchor + 1 until size).none { placed[anchor].overlaps(placed[it]) }) continue
        val fixed = (0..anchor).toSet()
        placed = PushDirection.preferred(0, 0).firstNotNullOfOrNull { placed.pushFrom(anchor, it, fixed) } ?: return null
    }
    return placed
}

private fun List<CanvasCluster>.pushFrom(
    active: Int,
    direction: PushDirection,
    fixed: Set<Int>,
): List<CanvasCluster>? {
    val placed = toMutableList()
    val pending = ArrayDeque<Int>()
    pending.add(active)
    while (pending.isNotEmpty()) {
        val source = pending.removeFirst()
        for (target in placed.indices) {
            if (source == target || !placed[source].overlaps(placed[target])) continue
            if (target in fixed) return null
            placed[target] = placed[source].push(placed[target], direction) ?: return null
            if (target !in pending) pending.add(target)
        }
    }
    return placed
}
