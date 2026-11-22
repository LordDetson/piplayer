package io.github.lorddetson.piplayer.ui.customization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.UI_CANVAS_MAX_ITEMS
import io.github.lorddetson.piplayer.settings.UI_CANVAS_MIN_SIZE
import io.github.lorddetson.piplayer.settings.UI_CANVAS_UNITS
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiCanvasState
import io.github.lorddetson.piplayer.settings.UiSurface
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class UiCanvasComponent(
    val id: String,
    val label: Int,
    val width: Dp = 48.dp,
    val height: Dp = 48.dp,
    val fillWidth: Boolean = false,
    val customizableIcon: Boolean = false,
    val content: @Composable () -> Unit,
)

internal val LocalUiCatalog = staticCompositionLocalOf<List<UiCanvasComponent>> { emptyList() }
internal val LocalUiEditEntry = staticCompositionLocalOf<((String) -> Unit)?> { null }

private data class EditSession(val initial: UiCanvasLayout, val draft: UiCanvasLayout, val reset: Boolean = false)

@Composable
internal fun EditableUiPanel(
    surface: UiSurface,
    themeId: String?,
    components: List<UiCanvasComponent>,
    initialRows: List<List<String>>,
    height: Dp,
    modifier: Modifier = Modifier,
    onSaveFailed: suspend () -> Unit = {},
    onAppliedIds: (List<String>?) -> Unit = {},
    canvasChrome: @Composable () -> Unit = {},
    stock: @Composable () -> Unit,
) {
    val application = LocalContext.current.applicationContext
    val settings = remember(application) { UiCanvasSettings(application) }
    val record by settings.state.collectAsStateWithLifecycle<UiCanvasState?>(initialValue = null)
    val scope = rememberCoroutineScope()
    val active = record?.takeIf { it.themeId == themeId }
    val persisted = active?.layouts?.panels?.get(surface)
    val catalog = (components + LocalUiCatalog.current).distinctBy(UiCanvasComponent::id).associateBy(UiCanvasComponent::id)
    var session by remember(themeId, surface) { mutableStateOf<EditSession?>(null) }
    var selected by remember(themeId, surface) { mutableStateOf<String?>(null) }
    var saving by remember(themeId, surface) { mutableStateOf(false) }
    var inspecting by remember(themeId, surface) { mutableStateOf(false) }
    val appliedCallback by rememberUpdatedState(onAppliedIds)
    LaunchedEffect(themeId, active != null, persisted) {
        if (active != null) appliedCallback(persisted?.items?.map(UiCanvasItem::id))
    }
    BackHandler(session != null) { if (!saving) { session = null; inspecting = false } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth <= 0.dp || height <= 0.dp) {
            stock()
            return@BoxWithConstraints
        }
        val canvasWidth = maxWidth
        val start: ((String) -> Unit)? = if (active == null) null else { id ->
            val initial = persisted ?: defaultCanvas(initialRows, catalog, canvasWidth, height)
            session = EditSession(initial, initial)
            selected = id.takeIf { sought -> initial.items.any { it.id == sought } }
        }
        val draft = session
        val layout = draft?.draft ?: persisted
        CompositionLocalProvider(LocalUiEditEntry provides start) {
            if (layout == null) {
                stock()
                return@CompositionLocalProvider
            }
            fun mutate(update: (UiCanvasLayout) -> UiCanvasLayout) {
                if (!saving) session = session?.let { it.copy(draft = update(it.draft), reset = false) }
            }
            val selectedItem = layout.items.firstOrNull { it.id == selected }
            val selectedComponent = catalog[selected]
            val scroll = if (surface == UiSurface.NOW_PLAYING) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(modifier.fillMaxWidth().then(scroll)) {
                canvasChrome()
                if (draft != null) {
                    UiEditorToolbar(
                        layout, catalog, selectedItem, saving,
                        onAdd = { component ->
                            mutate { it.add(addedItem(component, canvasWidth, height)) }
                            selected = component.id
                        },
                        onSelect = { selected = it },
                        onRaise = { id -> mutate { it.raise(id) } },
                        onLower = { id -> mutate { it.lower(id) } },
                        onHide = { id ->
                            mutate { it.hide(id) }
                            selected = null
                        },
                        onInspect = { inspecting = true },
                        onReset = {
                            session = draft.copy(draft = defaultCanvas(initialRows, catalog, canvasWidth, height), reset = true)
                            selected = null
                        },
                        onCancel = { session = null; inspecting = false },
                        onDone = {
                            if (draft.draft == draft.initial && !draft.reset) session = null
                            else if (themeId != null && !saving) scope.launch {
                                saving = true
                                if (settings.save(themeId, surface, draft.draft.takeUnless { draft.reset })) session = null
                                else onSaveFailed()
                                saving = false
                            }
                        },
                    )
                } else if (layout.items.none { it.id in catalog }) {
                    TextButton(onClick = { start?.invoke("") }, enabled = start != null) {
                        Text(stringResource(R.string.ui_edit_mode))
                    }
                }
                UiCanvas(
                    layout, catalog, canvasWidth, height, draft != null, selectedItem,
                    select = { selected = it },
                    move = { id, x, y -> mutate { it.move(id, x, y) } },
                    resize = { id, x, y -> mutate { it.resize(id, x, y) } },
                    snap = { id -> mutate { it.snap(id) } },
                )
            }
            if (inspecting && draft != null && selectedItem != null && selectedComponent != null) {
                key(selectedItem.id) {
                    UiComponentInspector(
                        selectedItem, selectedComponent,
                        onDismiss = { inspecting = false },
                        onUpdate = { padding, style ->
                            mutate { it.withPadding(selectedItem.id, padding).withStyle(selectedItem.id, style) }
                            inspecting = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UiEditorToolbar(
    layout: UiCanvasLayout,
    catalog: Map<String, UiCanvasComponent>,
    selected: UiCanvasItem?,
    saving: Boolean,
    onAdd: (UiCanvasComponent) -> Unit,
    onSelect: (String) -> Unit,
    onRaise: (String) -> Unit,
    onLower: (String) -> Unit,
    onHide: (String) -> Unit,
    onInspect: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var selectingLayer by remember { mutableStateOf(false) }
    val available = catalog.values.filter { candidate -> layout.items.none { it.id == candidate.id } }
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone, enabled = !saving, modifier = Modifier.testTag("ui_edit_done")) {
                Text(stringResource(R.string.ui_edit_done))
            }
            Box {
                TextButton(onClick = { adding = true }, enabled = !saving && available.isNotEmpty() && layout.items.size < UI_CANVAS_MAX_ITEMS) {
                    Text(stringResource(R.string.ui_edit_add))
                }
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    available.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(stringResource(item.label)) },
                            onClick = { adding = false; onAdd(item) },
                            modifier = Modifier.testTag("ui_add_" + item.id),
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { selectingLayer = true }, enabled = !saving && layout.items.isNotEmpty()) {
                    Text(stringResource(R.string.ui_edit_layers))
                }
                DropdownMenu(expanded = selectingLayer, onDismissRequest = { selectingLayer = false }) {
                    layout.items.asReversed().forEach { item ->
                        catalog[item.id]?.let { component ->
                            DropdownMenuItem(
                                text = { Text(stringResource(component.label)) },
                                onClick = { selectingLayer = false; onSelect(item.id) },
                                modifier = Modifier.testTag("ui_layer_" + item.id),
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onReset, enabled = !saving, modifier = Modifier.testTag("ui_edit_reset")) {
                Text(stringResource(R.string.ui_edit_reset))
            }
            TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.ui_edit_cancel)) }
        }
        if (selected != null) {
            val index = layout.items.indexOf(selected)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onInspect, enabled = !saving, modifier = Modifier.testTag("ui_edit_inspect")) {
                    Text(stringResource(R.string.ui_edit_properties))
                }
                TextButton(onClick = { onHide(selected.id) }, enabled = !saving, modifier = Modifier.testTag("ui_edit_hide")) {
                    Text(stringResource(R.string.ui_edit_hide))
                }
                TextButton(onClick = { onRaise(selected.id) }, enabled = !saving && index < layout.items.lastIndex) {
                    Text(stringResource(R.string.ui_edit_forward))
                }
                TextButton(onClick = { onLower(selected.id) }, enabled = !saving && index > 0) {
                    Text(stringResource(R.string.ui_edit_backward))
                }
            }
        }
    }
}

@Composable
private fun UiCanvas(
    layout: UiCanvasLayout,
    catalog: Map<String, UiCanvasComponent>,
    width: Dp,
    height: Dp,
    editing: Boolean,
    selected: UiCanvasItem?,
    select: (String) -> Unit,
    move: (String, Int, Int) -> Unit,
    resize: (String, Int, Int) -> Unit,
    snap: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val frameWidth = with(LocalDensity.current) { width.toPx() }
    val frameHeight = with(LocalDensity.current) { height.toPx() }
    Box(Modifier.fillMaxWidth().height(height).clipToBounds().testTag("ui_canvas")) {
        layout.items.forEachIndexed { index, item ->
            val component = catalog[item.id] ?: return@forEachIndexed
            key(item.id) {
                val bounds = item.bounds
                val itemWidth = width * bounds.width.toFraction()
                val itemHeight = height * bounds.height.toFraction()
                val isSelected = item.id == selected?.id
                val background = item.style.background?.let { Color(it or 0xff000000.toInt()) } ?: Color.Transparent
                val editEntry = LocalUiEditEntry.current
                var menu by remember { mutableStateOf(false) }
                val componentLabel = stringResource(component.label)
                val activation = if (!editing && editEntry != null) Modifier.customizeOnHold { menu = true } else Modifier
                val motion = if (!editing) Modifier.uiControlMotion(item.style.motion) else Modifier
                Box(
                    Modifier.absoluteOffset(width * bounds.x.toFraction(), height * bounds.y.toFraction())
                        .zIndex(index.toFloat())
                        .size(itemWidth, itemHeight)
                        .then(if (!editing) Modifier.testTag("ui_component_" + item.id) else Modifier)
                        .then(motion)
                        .then(activation)
                        .clipToBounds()
                        .background(background),
                    contentAlignment = Alignment.Center,
                ) {
                    val padding = item.padding
                    val contentFrame = Modifier.fillMaxSize().padding(
                        start = minOf(padding.start.dp, itemWidth / 2),
                        top = minOf(padding.top.dp, itemHeight / 2),
                        end = minOf(padding.end.dp, itemWidth / 2),
                        bottom = minOf(padding.bottom.dp, itemHeight / 2),
                    )
                    CompositionLocalProvider(LocalUiControlStyle provides item.style) {
                        CanvasComponentContents(component,
                            contentFrame.then(if (editing) Modifier.clearAndSetSemantics {} else Modifier))
                    }
                    if (editing) {
                        Box(Modifier.matchParentSize()
                            .border(1.dp, accent.copy(alpha = if (isSelected) 1f else 0.4f))
                            .clickable { select(item.id) }
                            .canvasDrag(item.id, frameWidth, frameHeight, onStart = { select(item.id) },
                                onDelta = move, onEnd = { snap(item.id) })
                            .semantics { contentDescription = componentLabel }
                            .testTag("ui_component_" + item.id))
                        if (isSelected) {
                            Box(Modifier.align(Alignment.BottomEnd).size(24.dp).background(accent, CircleShape)
                                .canvasDrag(item.id, frameWidth, frameHeight, onDelta = resize)
                                .testTag("ui_resize_" + item.id))
                        }
                    } else if (editEntry != null) {
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_edit_mode)) },
                                onClick = { menu = false; editEntry(item.id) },
                                modifier = Modifier.testTag("ui_edit_mode"),
                            )
                        }
                    }
                }
            }
        }
        if (editing && selected != null) {
            val (x, y) = layout.guides(selected.id)
            val bounds = selected.bounds
            Canvas(Modifier.matchParentSize().zIndex((layout.items.size + 1).toFloat())) {
                x.filter { guide -> bounds.horizontalAnchors.any { abs(guide - it) <= 9 } }.forEach {
                    val at = size.width * it.toFraction()
                    drawLine(accent, Offset(at, 0f), Offset(at, size.height), strokeWidth = 1.dp.toPx())
                }
                y.filter { guide -> bounds.verticalAnchors.any { abs(guide - it) <= 9 } }.forEach {
                    val at = size.height * it.toFraction()
                    drawLine(accent, Offset(0f, at), Offset(size.width, at), strokeWidth = 1.dp.toPx())
                }
            }
        }
    }
}

/** Measure button chrome at its design size, then scale its drawing and hit region together. */
@Composable
private fun CanvasComponentContents(component: UiCanvasComponent, modifier: Modifier) {
    if (!component.customizableIcon) {
        Box(modifier, contentAlignment = Alignment.Center) { component.content() }
        return
    }
    Layout(modifier = modifier, content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { component.content() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val designWidth = component.width.roundToPx().coerceAtLeast(1)
        val designHeight = component.height.roundToPx().coerceAtLeast(1)
        val button = children.single().measure(Constraints.fixed(designWidth, designHeight))
        val scale = minOf(width.toFloat() / designWidth, height.toFloat() / designHeight)
        layout(width, height) {
            if (scale > 0f) {
                button.placeWithLayer(
                    ((width - designWidth * scale) / 2).roundToInt(),
                    ((height - designHeight * scale) / 2).roundToInt(),
                ) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun Modifier.canvasDrag(
    id: String,
    width: Float,
    height: Float,
    onStart: () -> Unit = {},
    onDelta: (String, Int, Int) -> Unit,
    onEnd: () -> Unit = {},
): Modifier {
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(id, width, height) {
        var leftover = Offset.Zero
        detectDragGestures(
            onDragStart = { leftover = Offset.Zero; start() },
            onDragEnd = { end() },
            onDragCancel = { end() },
        ) { change, amount ->
            change.consume()
            leftover += Offset(amount.x / width * UI_CANVAS_UNITS, amount.y / height * UI_CANVAS_UNITS)
            val x = leftover.x.roundToInt()
            val y = leftover.y.roundToInt()
            leftover -= Offset(x.toFloat(), y.toFloat())
            if (x != 0 || y != 0) delta(id, x, y)
        }
    }
}

private fun Int.toFraction(): Float = toFloat() / UI_CANVAS_UNITS

private fun Dp.unitsWithin(total: Dp): Int =
    (value / total.value * UI_CANVAS_UNITS).roundToInt().coerceIn(UI_CANVAS_MIN_SIZE, UI_CANVAS_UNITS)

private fun defaultCanvas(rows: List<List<String>>, catalog: Map<String, UiCanvasComponent>, width: Dp, height: Dp): UiCanvasLayout {
    val componentRows = rows.map { row -> row.mapNotNull(catalog::get) }.filter(List<UiCanvasComponent>::isNotEmpty)
    val totalHeight = componentRows.sumOf { row -> row.maxOf { it.height.value }.toDouble() }.toFloat().dp
    val heightScale = if (totalHeight > height) height.value / totalHeight.value else 1f
    var y = 0
    val items = buildList {
        componentRows.forEach { row ->
            val fixedWidth = row.filterNot(UiCanvasComponent::fillWidth).sumOf { it.width.value.toDouble() }.toFloat().dp
            val flexibleCount = row.count(UiCanvasComponent::fillWidth)
            val flexibleWidth = if (flexibleCount == 0) 0.dp else ((width - fixedWidth) / flexibleCount).coerceAtLeast(20.dp)
            val preferredWidth = fixedWidth + flexibleWidth * flexibleCount
            val widthScale = if (preferredWidth > width) width.value / preferredWidth.value else 1f
            var x = 0
            row.forEach { component ->
                val w = ((if (component.fillWidth) flexibleWidth else component.width) * widthScale).unitsWithin(width)
                val h = (component.height * heightScale).unitsWithin(height)
                add(UiCanvasItem(component.id, UiCanvasBounds(x.coerceAtMost(UI_CANVAS_UNITS - w),
                    y.coerceAtMost(UI_CANVAS_UNITS - h), w, h)))
                x += w
            }
            y += (row.maxOf { it.height } * heightScale).unitsWithin(height)
        }
    }
    return UiCanvasLayout(items.distinctBy(UiCanvasItem::id).take(UI_CANVAS_MAX_ITEMS))
}

private fun addedItem(component: UiCanvasComponent, width: Dp, height: Dp): UiCanvasItem {
    val w = if (component.fillWidth) UI_CANVAS_UNITS else component.width.unitsWithin(width)
    val h = component.height.unitsWithin(height)
    return UiCanvasItem(component.id, UiCanvasBounds((UI_CANVAS_UNITS - w) / 2, (UI_CANVAS_UNITS - h) / 2, w, h))
}
