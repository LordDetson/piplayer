package io.github.lorddetson.piplayer.ui.customization

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.lorddetson.piplayer.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class UiEditorAction(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val tag: String,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

private val RingButtonSize = 48.dp
private val RingRadius = 72.dp
private val RingMargin = 4.dp
private val RingPopupProperties = PopupProperties(
    focusable = false,
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    excludeFromSystemGesture = false,
    clippingEnabled = false,
)

@Composable
internal fun UiEditorRing(actions: List<UiEditorAction>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val geometry = RingGeometry(
        buttonSize = with(density) { RingButtonSize.roundToPx() },
        radius = with(density) { RingRadius.roundToPx() },
        margin = with(density) { RingMargin.roundToPx() },
        insets = IntRect(
            insets.getLeft(density, direction), insets.getTop(density),
            insets.getRight(density, direction), insets.getBottom(density),
        ),
    )
    val viewport = remember { RingViewport() }
    var preferredCenter by remember { mutableStateOf<Offset?>(null) }
    var expanded by remember { mutableStateOf(true) }
    var orbitSteps by remember { mutableIntStateOf(0) }
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, tween(180), label = "Editor ring reveal")
    val orbit by animateFloatAsState(orbitSteps.toFloat(), tween(230), label = "Editor ring rotation")
    val swipeThreshold = with(density) { 28.dp.toPx() }

    if (expanded || reveal > 0f) {
        actions.forEachIndexed { index, action ->
            key(action.tag) {
                val angle = -PI / 2 - 2 * PI * (index + orbit) / actions.size
                val radial = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val depth = 1f - radial.x.coerceAtLeast(0f) * 0.47f
                val position = RingPosition(geometry, preferredCenter, true, radial * (0.38f + reveal * 0.62f))
                Popup(position, properties = RingPopupProperties) {
                    val rotation = if (expanded && actions.size > 1) {
                        Modifier.orbitSwipe(radial, swipeThreshold) { orbitSteps += it }
                    } else Modifier
                    RingIconButton(
                        action,
                        Modifier.testTag(action.tag).semantics { if (action.selected) selected = true }.then(rotation),
                        depth = depth,
                        reveal = reveal,
                        interactive = expanded,
                    )
                }
            }
        }
    }

    Popup(RingPosition(geometry, preferredCenter, expanded, Offset.Zero, viewport), properties = RingPopupProperties) {
        Box(modifier.testTag("ui_editor_ring")) {
            RingIconButton(
                action = UiEditorAction(
                    R.drawable.ic_ui_edit_pen,
                    if (expanded) R.string.ui_editor_ring_collapse else R.string.ui_editor_ring_expand,
                    "ui_editor_ring_handle",
                ) {
                    preferredCenter = geometry.centerIn(viewport.size, preferredCenter, expanded, direction)
                    expanded = !expanded
                },
                modifier = Modifier.testTag("ui_editor_ring_handle")
                    .pointerInput(geometry, expanded, direction) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            val current = geometry.centerIn(viewport.size, preferredCenter, expanded, direction)
                            preferredCenter = geometry.centerIn(viewport.size, current + amount, expanded, direction)
                        }
                    },
                depth = 1f,
                isHandle = true,
            )
        }
    }
}

@Composable
private fun RingIconButton(
    action: UiEditorAction,
    modifier: Modifier,
    depth: Float,
    reveal: Float = 1f,
    interactive: Boolean = true,
    isHandle: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when {
        isHandle && colors.surface.luminance() < 0.5f -> colors.surfaceContainerLow to colors.onSurface
        isHandle -> colors.inverseSurface to colors.inverseOnSurface
        action.selected -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurface
    }
    val opacity = if (isHandle || action.selected) 0.85f else 0.50f + depth * 0.33f
    IconButton(
        modifier = modifier.size(RingButtonSize),
        onClick = action.onClick,
        enabled = action.enabled && interactive,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = foreground,
            disabledContentColor = foreground.copy(alpha = 0.38f),
        ),
    ) {
        Box(
            Modifier.size(44.dp).graphicsLayer { alpha = reveal }
                .background(background.copy(alpha = opacity), CircleShape)
                .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(action.icon), stringResource(action.label), Modifier.size(21.dp))
        }
    }
}

@Composable
private fun Modifier.orbitSwipe(radial: Offset, threshold: Float, onTurn: (Int) -> Unit): Modifier {
    val direction by rememberUpdatedState(radial)
    val turn by rememberUpdatedState(onTurn)
    return pointerInput(threshold) {
        var tangent = Offset.Zero
        var displacement = 0f
        detectDragGestures(
            onDragStart = {
                tangent = Offset(direction.y, -direction.x)
                displacement = 0f
            },
            onDragEnd = {
                if (abs(displacement) >= threshold) turn(if (displacement > 0f) 1 else -1)
            },
        ) { change, amount ->
            change.consume()
            displacement += amount.x * tangent.x + amount.y * tangent.y
        }
    }
}

private class RingViewport(var size: IntSize = IntSize.Zero)

// Each button has its own popup so taps between buttons reach the editor below.
private data class RingPosition(
    val geometry: RingGeometry,
    val preferredCenter: Offset?,
    val expanded: Boolean,
    val directionFromCenter: Offset,
    val viewport: RingViewport? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        viewport?.size = windowSize
        val center = geometry.centerIn(windowSize, preferredCenter, expanded, layoutDirection)
        val offset = directionFromCenter * geometry.radiusIn(windowSize).toFloat()
        return IntOffset(
            (center.x + offset.x - popupContentSize.width / 2f).roundToInt(),
            (center.y + offset.y - popupContentSize.height / 2f).roundToInt(),
        )
    }
}

private data class RingGeometry(val buttonSize: Int, val radius: Int, val margin: Int, val insets: IntRect) {
    fun radiusIn(windowSize: IntSize): Int {
        val bounds = availableBounds(windowSize)
        return min(radius, ((min(bounds.width, bounds.height) - buttonSize) / 2 - margin).coerceAtLeast(0))
    }

    fun centerIn(windowSize: IntSize, preferred: Offset?, expanded: Boolean, direction: LayoutDirection): Offset {
        val bounds = availableBounds(windowSize)
        val extent = buttonSize / 2f + margin + if (expanded) radiusIn(windowSize) else 0
        val horizontalExtent = extent.coerceAtMost(bounds.width / 2f)
        val verticalExtent = extent.coerceAtMost(bounds.height / 2f)
        val minX = bounds.left + horizontalExtent
        val maxX = bounds.right - horizontalExtent
        val minY = bounds.top + verticalExtent
        val maxY = bounds.bottom - verticalExtent
        val startX = if (direction == LayoutDirection.Ltr) maxX else minX
        return Offset((preferred?.x ?: startX).coerceIn(minX, maxX), (preferred?.y ?: maxY).coerceIn(minY, maxY))
    }

    private fun availableBounds(windowSize: IntSize): IntRect {
        val left = insets.left.coerceAtMost(windowSize.width)
        val top = insets.top.coerceAtMost(windowSize.height)
        return IntRect(left, top, (windowSize.width - insets.right).coerceAtLeast(left), (windowSize.height - insets.bottom).coerceAtLeast(top))
    }
}
