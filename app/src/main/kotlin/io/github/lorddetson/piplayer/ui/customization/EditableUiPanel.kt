package io.github.lorddetson.piplayer.ui.customization

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureProblem
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiLabelDisplay
import io.github.lorddetson.piplayer.settings.UiPlacementMode
import io.github.lorddetson.piplayer.settings.UiResizeEdge
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
    val alignableText: Boolean = false,
    val labelDisplay: Boolean = false,
    val tabDirection: Boolean = false,
    val preserveGestures: Boolean = false,
    val scaleBlock: Boolean = false,
    val addable: Boolean = true,
    val onClick: (() -> Unit)? = null,
    val stockContext: Boolean = false,
    val content: @Composable () -> Unit,
)

internal val LocalUiCatalog = staticCompositionLocalOf<List<UiCanvasComponent>> { emptyList() }
internal val LocalUiEditEntry = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal val LocalUiGestureEditEntry = staticCompositionLocalOf<((String) -> Unit)?> { null }
internal val LocalUiGestureHideGuard = staticCompositionLocalOf<((String) -> Boolean)?> { null }
internal val LocalLibraryWorkspaceEntry = staticCompositionLocalOf<((String) -> Unit)?> { null }

private data class EditSession(
    val initial: UiCanvasLayout,
    val draft: UiCanvasLayout,
    val anchored: Boolean,
    val previousOverrides: UiCanvasLayout?,
    val reset: Boolean = false,
)

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
    legacyStockIds: Set<String> = emptySet(),
    legacyVisibilityGroups: Map<String, Set<String>> = emptyMap(),
    hiddenByParent: Set<String> = emptySet(),
    gestureHiddenByParent: Set<String> = hiddenByParent,
    viewModeModifier: Modifier = Modifier,
    stockGestures: List<UiGestureBinding> = emptyList(),
    canvasChrome: @Composable () -> Unit = {},
    stock: @Composable () -> Unit,
) {
    val application = LocalContext.current.applicationContext
    val settings = remember(application) { UiCanvasSettings(application) }
    val record by settings.state.collectAsStateWithLifecycle<UiCanvasState?>(initialValue = null)
    val scope = rememberCoroutineScope()
    val active = record?.takeIf { it.themeId == themeId }
    val persisted = active?.layouts?.panels?.get(surface)
    val storedProfile = active?.layouts?.gestures ?: UiGestureProfile()
    var savedProfile by remember(themeId, surface) { mutableStateOf<UiGestureProfile?>(null) }
    val profile = savedProfile ?: storedProfile
    LaunchedEffect(storedProfile, savedProfile) { if (savedProfile == storedProfile) savedProfile = null }
    val catalog = (components + LocalUiCatalog.current).distinctBy(UiCanvasComponent::id).associateBy(UiCanvasComponent::id)
    val stockIds = initialRows.flatten().distinct()
    val geometry = remember(themeId, surface) { StockUiGeometry() }
    var session by remember(themeId, surface) { mutableStateOf<EditSession?>(null) }
    var selected by remember(themeId, surface) { mutableStateOf<String?>(null) }
    var saving by remember(themeId, surface) { mutableStateOf(false) }
    var inspecting by remember(themeId, surface) { mutableStateOf(false) }
    var picked by remember(themeId, surface) { mutableStateOf<Set<String>?>(null) }
    var gestureBase by remember(themeId, surface) { mutableStateOf<UiCanvasLayout?>(null) }
    var blocked by remember(themeId, surface) { mutableStateOf(false) }
    var gestureEditor by remember(themeId, surface) { mutableStateOf<String?>(null) }
    var gestureDraft by remember(themeId, surface) { mutableStateOf<UiGestureProfile?>(null) }
    var rejectedGeometry by remember(themeId, surface) { mutableStateOf<String?>(null) }
    val owner = LocalUiGestureRuntime.current?.owner ?: remember { UiGestureContactOwner() }
    val registry = LocalUiGesturePanelRegistry.current ?: remember { UiGesturePanelRegistry() }
    val appliedCallback by rememberUpdatedState(onAppliedIds)
    LaunchedEffect(themeId, active != null, persisted, stockIds) {
        if (active != null) appliedCallback(persisted?.let { saved ->
            val changed = saved.items.map(UiCanvasItem::id)
            saved.hiddenStock?.let { hidden -> (stockIds.filterNot(hidden::contains) + changed).distinct() } ?: changed
        })
    }
    BackHandler(session != null) { if (!saving) { session = null; inspecting = false; gestureEditor = null } }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth <= 0.dp || height <= 0.dp) {
            stock()
            return@BoxWithConstraints
        }
        val canvasWidth = maxWidth
        val start: ((String) -> Unit)? = if (active == null) null else { id ->
            val sampled = geometry.snapshot(stockIds)
            val anchored = persisted?.hiddenStock != null || (persisted == null && sampled.items.isNotEmpty())
            val previous = persisted.takeIf { anchored }
            val initial = if (anchored) {
                val changedIds = previous?.items.orEmpty().map(UiCanvasItem::id).toSet()
                val hidden = previous?.hiddenStock.orEmpty()
                (previous ?: UiCanvasLayout(emptyList())).copy(
                    items = sampled.items.filterNot { it.id in hidden || it.id in changedIds } + previous?.items.orEmpty(),
                    hiddenStock = null,
                )
            } else persisted ?: defaultCanvas(initialRows, catalog, canvasWidth, height)
            session = EditSession(initial, initial, anchored, previous)
            selected = id.takeIf { sought -> initial.items.any { it.id == sought } }
            picked = null
            gestureBase = null
            gestureDraft = null
            blocked = false
        }
        val enterGestures: ((String) -> Unit)? = start?.let { begin ->
            { id ->
                if (session == null) begin(id)
                gestureEditor = id
            }
        }
        val currentGestureEntry by rememberUpdatedState(enterGestures)
        val runtime = remember(themeId, surface, owner) {
            UiGestureRuntime(surface, owner, { currentGestureEntry?.invoke(it) }, scope)
        }
        DisposableEffect(registry, runtime) {
            registry.panels[surface] = runtime
            onDispose {
                runtime.dispose()
                if (registry.panels[surface] === runtime) registry.panels.remove(surface)
            }
        }
        val draft = session
        val requestedLayout = draft?.draft ?: persisted
        val desiredProfile = gestureDraft.takeIf { draft != null } ?: profile
        val declaredContexts = components.filter(UiCanvasComponent::stockContext).map {
            stockGesture(it.id, UiBuiltInGesture.LONG_PRESS, UiGestureAction(UiGestureActionId.CONTEXT_MENU, targetId = it.id))
        }
        fun environment(
            candidate: UiCanvasLayout?,
            editing: Boolean = false,
            extraHidden: Set<String> = emptySet(),
        ): UiGestureEnvironment {
            val overrideItems = candidate?.items.orEmpty().map(UiCanvasItem::id).toSet()
            val mountedLegacy = legacyStockIds.filterTo(mutableSetOf()) { it in runtime.mounts && it !in candidate?.hiddenStock.orEmpty() }
            val available = when {
                editing -> overrideItems + mountedLegacy
                candidate == null -> stockIds.filterTo(mutableSetOf()) { it in runtime.mounts } + mountedLegacy
                candidate.hiddenStock != null -> stockIds.filterTo(mutableSetOf()) {
                    it !in candidate.hiddenStock && it in runtime.mounts
                } + overrideItems + mountedLegacy
                else -> overrideItems
            }
            return UiGestureEnvironment(
                (available intersect catalog.keys) - gestureHiddenByParent - extraHidden,
                candidate?.groups.orEmpty(),
                (stockGestures + declaredContexts + runtime.registeredStock).distinctBy(UiGestureBinding::slot),
            )
        }
        val requestedEnvironment = environment(requestedLayout, editing = draft != null)
        val invalidRequested = profile.validate(surface, requestedEnvironment) as? UiGestureProblem.MissingActions
        // Keep malformed imports editable while showing the component whose stock gesture they displaced.
        val projectStock = draft == null && persisted != null && invalidRequested != null &&
            requestedEnvironment.stock.any { binding ->
                binding.action in invalidRequested.actions && !requestedEnvironment.reaches(binding.slot.target) &&
                    binding.slot.target.members.any { it in stockIds || it in legacyStockIds }
            }
        val layout = requestedLayout.takeUnless { projectStock }
        val gestureEnvironment = if (projectStock) environment(null) else requestedEnvironment
        val effectiveProfile = if (projectStock) profile.copy(surfaces = profile.surfaces - surface)
            else desiredProfile.safeForDisplay(surface, gestureEnvironment)
        val workspaceEntry = if (surface == UiSurface.LIBRARY_WORKSPACE) start else LocalLibraryWorkspaceEntry.current
        val inheritedLabels = LocalUiLabelDisplays.current
        val labels = if (surface == UiSurface.LIBRARY) {
            inheritedLabels.filterKeys { !it.startsWith("tab:") } + layout?.labelDisplays.orEmpty()
        } else inheritedLabels + layout?.labelDisplays.orEmpty()
        SideEffect {
            runtime.environment = gestureEnvironment
            runtime.profile = effectiveProfile
            runtime.userProfile = desiredProfile
            runtime.enabled = draft == null
            runtime.catalog = catalog
            runtime.nestedContainers = legacyStockIds
        }
        val hideGuard: (String) -> Boolean = { id ->
            val detached = persisted?.items.orEmpty().map(UiCanvasItem::id).toSet()
            val removed = setOf(id) + (legacyVisibilityGroups[id].orEmpty() - detached)
            val candidate = environment(layout, editing = draft != null, extraHidden = removed)
            val problem = desiredProfile.prune(surface, candidate).validate(surface, candidate, previous = profile)
            (problem == null).also { if (!it) rejectedGeometry = id }
        }
        SideEffect { runtime.hide = hideGuard }
        CompositionLocalProvider(
            LocalUiEditEntry provides start,
            LocalUiGestureEditEntry provides enterGestures,
            LocalUiGestureHideGuard provides hideGuard,
            LocalUiGestureRuntime provides runtime,
            LocalUiGesturePanelRegistry provides registry,
            LocalUiGestureEditing provides (draft != null),
            LocalLibraryWorkspaceEntry provides workspaceEntry,
            LocalUiLabelDisplays provides labels,
        ) {
            catalog.values.filter { it.onClick != null }.forEach { component ->
                key(component.id) { registerUiGestureActions(component.id, click = component.onClick) }
            }
            val anchored = draft?.anchored ?: (layout?.hiddenStock != null)
            fun mutate(validateFor: String? = null, update: (UiCanvasLayout) -> UiCanvasLayout) {
                if (!saving) session = session?.let {
                    val updated = update(it.draft)
                    if (validateFor != null) {
                        val candidate = environment(updated, editing = true)
                        val remaining = desiredProfile.prune(surface, candidate)
                        val hidden = it.draft.items.map(UiCanvasItem::id).toSet() - candidate.components
                        val blockedChild = if (surface == UiSurface.LIBRARY_WORKSPACE) {
                            registry.panels.values.firstOrNull { child ->
                                child !== runtime && hidden.isNotEmpty() && child.userProfile.surfaces[child.surface] != null &&
                                    child.environment.let { environment ->
                                        val visible = environment.copy(components = environment.components.filterTo(mutableSetOf()) {
                                            workspaceSlotFor(it) !in hidden
                                        })
                                        child.userProfile.prune(child.surface, visible)
                                            .validate(child.surface, visible, previous = child.userProfile) != null
                                    }
                            }
                        } else null
                        if (blockedChild != null || remaining.validate(surface, candidate, previous = profile) != null) {
                            rejectedGeometry = validateFor
                            return@let it
                        }
                        if (remaining != desiredProfile) gestureDraft = remaining
                    }
                    if (updated == it.draft) it else it.copy(draft = updated, reset = false)
                }
            }
            fun select(id: String) {
                selected = id
                val selection = picked ?: return
                val members = layout?.members(id).orEmpty()
                picked = if (selection.containsAll(members)) selection - members else selection + members
            }
            fun beginTransform(id: String) {
                selected = id
                gestureBase = session?.draft
                blocked = false
            }
            fun transform(id: String, edge: UiResizeEdge?, x: Int, y: Int) {
                val base = gestureBase ?: return
                val result = if (edge == null) base.move(id, x, y) else base.resize(id, edge, x, y)
                blocked = base.placement == UiPlacementMode.PUSH && (x != 0 || y != 0) && result == base
                if (!blocked) mutate {
                    when {
                        result == base -> base
                        edge == null -> result.snap(id)
                        else -> result.snapResize(id, edge)
                    }
                }
            }
            fun endTransform() {
                gestureBase = null
                blocked = false
            }
            val selectedItem = layout?.items?.firstOrNull { it.id == selected }
            val selectedComponent = catalog[selected]
            val stockChanges = when {
                projectStock -> null
                draft?.reset == true -> null
                draft?.anchored == true -> draft.draft.stockOverrides(draft.initial, draft.previousOverrides, stockIds.toSet() + legacyStockIds)
                else -> persisted
            }
            val frameSize = geometry.frame
            val density = LocalDensity.current
            val drawingWidth = if (anchored) with(density) { frameSize.width.toDp() } else canvasWidth
            val drawingHeight = if (anchored) with(density) { frameSize.height.toDp() } else height

            Box(if (draft == null) viewModeModifier else Modifier) {
                if (layout == null || anchored) {
                    val masked = stockChanges?.hiddenStock.orEmpty() + stockChanges?.items.orEmpty().map(UiCanvasItem::id)
                    CompositionLocalProvider(LocalStockUiSlots provides
                        StockUiSlots(geometry, masked, runtime, stockChanges?.hiddenStock.orEmpty())) {
                        Box(Modifier.fillMaxWidth().onGloballyPositioned { geometry.frame = it.boundsInRoot() }) { stock() }
                    }
                } else {
                    val scroll = if (surface == UiSurface.NOW_PLAYING) Modifier.verticalScroll(rememberScrollState()) else Modifier
                    Column(modifier.fillMaxWidth().then(scroll)) {
                        canvasChrome()
                        UiCanvas(layout, catalog, drawingWidth, drawingHeight, draft != null, selectedItem, picked,
                            dragging = gestureBase != null, blocked = blocked,
                            select = ::select, onStart = ::beginTransform, transform = ::transform, onEnd = ::endTransform,
                            hiddenByParent = hiddenByParent,
                        )
                    }
                }
                if (anchored && layout != null && drawingWidth > 0.dp && drawingHeight > 0.dp) {
                    val detached = stockChanges?.items.orEmpty().map(UiCanvasItem::id).toSet()
                    UiCanvas(
                        layout, catalog, drawingWidth, drawingHeight, draft != null, selectedItem, picked,
                        dragging = gestureBase != null, blocked = blocked,
                        select = ::select, onStart = ::beginTransform, transform = ::transform, onEnd = ::endTransform,
                        modifier = Modifier.matchParentSize(),
                        drawOnly = detached,
                        stockGeometry = geometry,
                        hiddenByParent = hiddenByParent,
                    )
                }
                if (draft != null && layout != null) {
                    UiEditorControls(
                        layout, catalog, selectedItem, picked, saving,
                        onAdd = { component ->
                            mutate { it.add(addedItem(component, drawingWidth, drawingHeight)) }
                            val added = session?.draft?.items?.any { it.id == component.id } == true
                            if (added) selected = component.id
                            added
                        },
                        onSelect = ::select,
                        onGroup = {
                            val selection = picked
                            if (selection == null) picked = selected?.let(layout::members).orEmpty()
                            else {
                                if (selection.size >= 2) {
                                    mutate(selected ?: selection.first()) { it.group(selection) }
                                    selected = selected?.takeIf(selection::contains) ?: selection.first()
                                }
                                picked = null
                            }
                        },
                        onUngroup = { id -> mutate(id) { it.ungroup(id) } },
                        onSnap = { mutate { it.copy(snapping = !it.snapping) } },
                        onPlacement = { mode ->
                            mutate { it.withPlacement(mode) }
                            session?.draft?.placement == mode
                        },
                        onRaise = { id -> mutate { it.raise(id) } },
                        onLower = { id -> mutate { it.lower(id) } },
                        onHide = { id ->
                            mutate(id) { it.hide(id) }
                            if (session?.draft?.items?.none { it.id == id } == true) selected = null
                        },
                        onInspect = { inspecting = true },
                        onGestures = { selected?.let { gestureEditor = it } },
                        onReset = {
                            val initial = if (anchored) geometry.snapshot(stockIds) else defaultCanvas(initialRows, catalog, canvasWidth, height)
                            session = EditSession(initial, initial, anchored, previousOverrides = null, reset = true)
                            selected = null
                        },
                        onCancel = { session = null; inspecting = false; gestureEditor = null },
                        onDone = {
                            val changedLayout = draft.draft != draft.initial || draft.reset
                            if (!changedLayout && desiredProfile == profile) session = null
                            else if (themeId != null && !saving) scope.launch {
                                val changes = if (!changedLayout) persisted else (if (anchored) stockChanges else draft.draft)
                                    .takeUnless { draft.reset }
                                val candidate = environment(changes)
                                val pending = desiredProfile.prune(surface, candidate)
                                if (pending.validate(surface, candidate, previous = profile) != null) {
                                    rejectedGeometry = selected ?: stockIds.firstOrNull()
                                } else {
                                    saving = true
                                    val saved = if (pending.isEmpty && profile.isEmpty) settings.save(themeId, surface, changes)
                                    else settings.saveGestures(themeId, surface, changes, pending, candidate, expected = profile)
                                    if (saved) {
                                        savedProfile = pending
                                        session = null
                                    } else onSaveFailed()
                                    saving = false
                                }
                            }
                        },
                    )
                }
                if (layout != null && draft == null && (if (anchored) {
                    stockIds.all { it in persisted?.hiddenStock.orEmpty() } && persisted?.items.orEmpty().none { it.id in catalog }
                } else layout.items.none { it.id in catalog })) {
                    TextButton(onClick = { start?.invoke("") }, enabled = start != null, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(stringResource(R.string.ui_edit_mode))
                    }
                }
                if (draft == null && enterGestures != null &&
                    invalidRequested?.actions?.any { it.id == UiGestureActionId.CONTEXT_MENU } == true &&
                    effectiveProfile.resolve(surface, gestureEnvironment).none { it.action.id == UiGestureActionId.CONTEXT_MENU }
                ) {
                    val recovery = gestureEnvironment.components.firstOrNull()
                    if (recovery != null) TextButton(
                        onClick = { enterGestures(recovery) },
                        modifier = Modifier.align(Alignment.CenterEnd).testTag("gesture_recovery"),
                    ) { Text(stringResource(R.string.ui_gestures)) }
                }
            }
            gestureEditor?.let { id ->
                UiGestureEditor(id, surface, catalog, gestureEnvironment, desiredProfile, profile, runtime,
                    onApply = { gestureDraft = it; gestureEditor = null }, onDismiss = { gestureEditor = null })
            }
            rejectedGeometry?.let { id ->
                AlertDialog(
                    onDismissRequest = { rejectedGeometry = null },
                    title = { Text(stringResource(R.string.gesture_blocked_title)) },
                    text = { Text(stringResource(R.string.gesture_blocked_geometry)) },
                    confirmButton = {
                        TextButton(onClick = { rejectedGeometry = null; enterGestures?.invoke(id) }) {
                            Text(stringResource(R.string.ui_gestures))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { rejectedGeometry = null }) { Text(stringResource(R.string.ui_edit_cancel)) }
                    },
                )
            }
            if (inspecting && draft != null && selectedItem != null && selectedComponent != null) {
                key(selectedItem.id) {
                    val labelDisplay = labels[selectedItem.id] ?: selectedItem.style.labelDisplay
                    UiComponentInspector(
                        selectedItem.copy(style = selectedItem.style.copy(labelDisplay = labelDisplay)), selectedComponent,
                        onDismiss = { inspecting = false },
                        onUpdate = { padding, style ->
                            mutate {
                                val update = it.withPadding(selectedItem.id, padding)
                                if (selectedComponent.labelDisplay) {
                                    update.withLabelDisplay(selectedItem.id, style.labelDisplay).withStyle(
                                        selectedItem.id, style.copy(labelDisplay = UiLabelDisplay.ICON_AND_TEXT),
                                    )
                                } else update.withStyle(selectedItem.id, style)
                            }
                            inspecting = false
                        },
                    )
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
    picked: Set<String>?,
    dragging: Boolean,
    blocked: Boolean,
    select: (String) -> Unit,
    onStart: (String) -> Unit,
    transform: (String, UiResizeEdge?, Int, Int) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().height(height),
    drawOnly: Set<String>? = null,
    stockGeometry: StockUiGeometry? = null,
    hiddenByParent: Set<String> = emptySet(),
) {
    val accent = MaterialTheme.colorScheme.primary
    val selectionColor = if (blocked) MaterialTheme.colorScheme.error else accent
    val frameWidth = with(LocalDensity.current) { width.toPx() }
    val frameHeight = with(LocalDensity.current) { height.toPx() }
    Box(modifier.clipToBounds().testTag("ui_canvas")) {
        if (editing && drawOnly != null) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
        layout.items.forEachIndexed { index, item ->
            if (item.id in hiddenByParent) return@forEachIndexed
            val component = catalog[item.id] ?: return@forEachIndexed
            val drawContent = drawOnly == null || item.id in drawOnly
            if (!editing && !drawContent) return@forEachIndexed
            key(item.id) {
                val bounds = item.bounds
                val itemWidth = width * bounds.width.toFraction()
                val itemHeight = height * bounds.height.toFraction()
                val background = item.style.background?.let { Color(it or 0xff000000.toInt()) } ?: Color.Transparent
                val editEntry = LocalUiEditEntry.current
                val gestures = LocalUiGestureEditEntry.current
                val workspaceEntry = LocalLibraryWorkspaceEntry.current
                val workspaceSlot = workspaceSlotFor(item.id)
                val runtime = LocalUiGestureRuntime.current
                val ancestors = LocalUiGestureAncestors.current
                var menu by remember { mutableStateOf(false) }
                val componentLabel = stringResource(component.label)
                val openMenu: (() -> Unit)? = { menu = true }.takeIf { !editing && editEntry != null && !component.preserveGestures }
                val activation = Modifier.uiGestureTarget(
                    item.id, onClick = component.onClick.takeUnless { editing }, onHold = openMenu,
                    preserveMovement = component.preserveGestures,
                ).then(
                    if (openMenu != null && stockHoldUsesNative(item.id)) Modifier.customizeOnHold(onHold = openMenu)
                    else Modifier,
                )
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
                    if (drawContent) {
                        val padding = item.padding
                        val contentFrame = Modifier.fillMaxSize().padding(
                            start = minOf(padding.start.dp, itemWidth / 2),
                            top = minOf(padding.top.dp, itemHeight / 2),
                            end = minOf(padding.end.dp, itemWidth / 2),
                            bottom = minOf(padding.bottom.dp, itemHeight / 2),
                        )
                        CompositionLocalProvider(
                            LocalUiControlStyle provides item.style,
                            LocalUiGestureAncestors provides (ancestors + listOfNotNull(runtime?.let { it to item.id })),
                        ) {
                            CanvasComponentContents(component,
                                contentFrame.then(if (editing) Modifier.clearAndSetSemantics {} else Modifier),
                                stockGeometry?.slots?.get(item.id))
                        }
                    }
                    if (editing) {
                        val drag = if (picked == null) Modifier.canvasDrag(
                            item.id, frameWidth, frameHeight, onStart = { onStart(item.id) },
                            onDelta = { x, y -> transform(item.id, null, x, y) }, onEnd = onEnd,
                        ) else Modifier
                        Box(Modifier.matchParentSize()
                            .then(if (picked?.contains(item.id) == true) Modifier.border(2.dp, accent) else Modifier)
                            .clickable { select(item.id) }
                            .then(drag)
                            .semantics { contentDescription = componentLabel }
                            .testTag("ui_component_" + item.id))
                    } else if (editEntry != null) {
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_edit_mode)) },
                                onClick = { menu = false; editEntry(item.id) },
                                modifier = Modifier.testTag("ui_edit_mode"),
                            )
                            if (gestures != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_gestures)) },
                                onClick = { menu = false; gestures(item.id) },
                                modifier = Modifier.testTag("ui_edit_gestures"),
                            )
                            if (workspaceEntry != null && workspaceSlot != null && editEntry !== workspaceEntry) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ui_edit_library_workspace)) },
                                    onClick = { menu = false; workspaceEntry(workspaceSlot) },
                                    modifier = Modifier.testTag("ui_edit_library_workspace"),
                                )
                            }
                        }
                    }
                }
            }
        }
        val bounds = selected?.let { layout.boundsOf(layout.members(it.id)) }
        if (editing && picked == null && selected != null && bounds != null) {
            val frameZ = (layout.items.size + 1).toFloat()
            ResizeFrame(selected.id, bounds, width, height, Modifier.zIndex(frameZ), selectionColor,
                onStart = { onStart(selected.id) },
                onDelta = { edge, dx, dy -> transform(selected.id, edge, dx, dy) }, onEnd = onEnd)
            if (dragging && layout.snapping) {
                val (x, y) = layout.guides(selected.id)
                Canvas(Modifier.matchParentSize().zIndex(frameZ)) {
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
}

@Composable
private fun ResizeFrame(
    id: String,
    bounds: UiCanvasBounds,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    color: Color,
    onStart: () -> Unit,
    onDelta: (UiResizeEdge, Int, Int) -> Unit,
    onEnd: () -> Unit,
) {
    val frameWidth = width * bounds.width.toFraction()
    val frameHeight = height * bounds.height.toFraction()
    val hit = minOf(20.dp, frameWidth / 3, frameHeight / 3)
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    Box(modifier.absoluteOffset(width * bounds.x.toFraction(), height * bounds.y.toFraction())
        .size(frameWidth, frameHeight).border(1.dp, color)) {
        UiResizeEdge.entries.forEach { edge ->
            val targetWidth = if (edge.horizontal == 0) frameWidth - hit * 2 else hit
            val targetHeight = if (edge.vertical == 0) frameHeight - hit * 2 else hit
            val alignment = BiasAbsoluteAlignment(edge.horizontal.toFloat(), edge.vertical.toFloat())
            val suffix = if (edge == UiResizeEdge.BOTTOM_RIGHT) "" else ":" + edge.name.lowercase()
            Box(Modifier.align(alignment).size(targetWidth, targetHeight)
                .canvasDrag(id + suffix, widthPx, heightPx, onStart, { x, y -> onDelta(edge, x, y) }, onEnd)
                .testTag("ui_resize_" + id + suffix))
        }
    }
}

/** Measure button chrome at its design size, then scale its drawing and hit region together. */
@Composable
private fun CanvasComponentContents(component: UiCanvasComponent, modifier: Modifier, stockBounds: Rect?) {
    if (!component.customizableIcon && !component.scaleBlock) {
        Box(modifier, contentAlignment = Alignment.Center) { component.content() }
        return
    }
    Layout(modifier = modifier, content = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { component.content() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val design = stockBounds?.takeIf { component.scaleBlock && !it.isEmpty }
        val designWidth = (design?.width?.roundToInt() ?: if (component.scaleBlock) width else component.width.roundToPx()).coerceAtLeast(1)
        val designHeight = (design?.height?.roundToInt() ?: component.height.roundToPx()).coerceAtLeast(1)
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
    onDelta: (Int, Int) -> Unit,
    onEnd: () -> Unit = {},
): Modifier {
    val start by rememberUpdatedState(onStart)
    val delta by rememberUpdatedState(onDelta)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(id, width, height) {
        var displacement = Offset.Zero
        detectDragGestures(
            onDragStart = { displacement = Offset.Zero; start() },
            onDragEnd = { end() },
            onDragCancel = { end() },
        ) { change, amount ->
            change.consume()
            displacement += Offset(amount.x / width * UI_CANVAS_UNITS, amount.y / height * UI_CANVAS_UNITS)
            delta(displacement.x.roundToInt(), displacement.y.roundToInt())
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
