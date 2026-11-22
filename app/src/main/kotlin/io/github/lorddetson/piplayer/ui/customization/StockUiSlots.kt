package io.github.lorddetson.piplayer.ui.customization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.lorddetson.piplayer.settings.UI_CANVAS_MIN_SIZE
import io.github.lorddetson.piplayer.settings.UI_CANVAS_UNITS
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import kotlin.math.roundToInt

internal class StockUiGeometry {
    var frame by mutableStateOf(Rect.Zero)
    val slots = mutableMapOf<String, Rect>()

    fun snapshot(ids: List<String>): UiCanvasLayout {
        val bounds = frame
        if (bounds.width <= 0f || bounds.height <= 0f) return UiCanvasLayout(emptyList())
        return UiCanvasLayout(ids.distinct().mapNotNull { id ->
            val slot = slots[id]?.intersect(bounds)?.takeUnless { it.isEmpty } ?: return@mapNotNull null
            fun relative(position: Float, dimension: Float) = (position / dimension * UI_CANVAS_UNITS).roundToInt()
            val width = relative(slot.width, bounds.width).coerceIn(UI_CANVAS_MIN_SIZE, UI_CANVAS_UNITS)
            val height = relative(slot.height, bounds.height).coerceIn(UI_CANVAS_MIN_SIZE, UI_CANVAS_UNITS)
            val x = relative(slot.left - bounds.left, bounds.width).coerceIn(0, UI_CANVAS_UNITS - width)
            val y = relative(slot.top - bounds.top, bounds.height).coerceIn(0, UI_CANVAS_UNITS - height)
            UiCanvasItem(id, UiCanvasBounds(x, y, width, height))
        })
    }
}

internal data class StockUiSlots(val geometry: StockUiGeometry, val masked: Set<String>)

internal val LocalStockUiSlots = staticCompositionLocalOf<StockUiSlots?> { null }

@Composable
internal fun isStockUiSlotMasked(id: String): Boolean = id in LocalStockUiSlots.current?.masked.orEmpty()

@Composable
internal fun Modifier.recordStockUiSlot(id: String, owner: StockUiSlots? = LocalStockUiSlots.current): Modifier {
    val geometry = owner?.geometry ?: return this
    DisposableEffect(geometry, id) { onDispose { geometry.slots.remove(id) } }
    return onGloballyPositioned { geometry.slots[id] = it.boundsInRoot() }
}

/** A detached or hidden control reserves the space it had in the stock layout. */
@Composable
internal fun StockUiSlot(
    id: String,
    modifier: Modifier = Modifier,
    owner: StockUiSlots? = LocalStockUiSlots.current,
    content: @Composable () -> Unit,
) {
    if (owner == null) {
        Box(modifier) { content() }
        return
    }
    var size by remember(id) { mutableStateOf(IntSize.Zero) }
    val masked = id in owner.masked
    val decoration = if (masked && size == IntSize.Zero) {
        Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}
    } else Modifier
    Box(modifier.recordStockUiSlot(id, owner).onSizeChanged { size = it }.then(decoration)) {
        if (masked && size != IntSize.Zero) {
            with(LocalDensity.current) { Spacer(Modifier.size(size.width.toDp(), size.height.toDp())) }
        } else {
            content()
        }
    }
}
