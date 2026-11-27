package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabsSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Geometry is in thousandths of the safe panel area. Insets are logical dp. */
internal const val UI_CANVAS_UNITS = 1_000
internal const val UI_CANVAS_MIN_SIZE = 20
internal const val UI_CANVAS_MAX_INSET = 48
internal const val UI_CANVAS_MAX_ITEMS = 32
internal const val UI_ICON_MAX_PAYLOAD = 14_000
private const val THEME_ICON_BUDGET = 192_000
private const val THEME_GESTURE_BUDGET = 224 * 1024
private val componentId = Regex("[a-z][a-z0-9_.-]*:[a-z0-9_.-]+")

@Serializable
internal enum class UiSurface { LIBRARY, NOW_PLAYING, MINI_PLAYER, BOTTOM_NAVIGATION, LIBRARY_WORKSPACE }

@Serializable
internal data class UiCanvasBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isValid: Boolean get() = width in UI_CANVAS_MIN_SIZE..UI_CANVAS_UNITS && height in UI_CANVAS_MIN_SIZE..UI_CANVAS_UNITS &&
        x in 0..(UI_CANVAS_UNITS - width) && y in 0..(UI_CANVAS_UNITS - height)
    val horizontalAnchors: List<Int> get() = listOf(x, x + width / 2, x + width)
    val verticalAnchors: List<Int> get() = listOf(y, y + height / 2, y + height)

    fun move(dx: Int, dy: Int) = copy(
        x = x + dx.coerceIn(-x, UI_CANVAS_UNITS - x - width),
        y = y + dy.coerceIn(-y, UI_CANVAS_UNITS - y - height),
    )
}

@Serializable
internal data class UiCanvasPadding(val start: Int = 0, val top: Int = 0, val end: Int = 0, val bottom: Int = 0) {
    val isValid: Boolean get() = listOf(start, top, end, bottom).all { it in 0..UI_CANVAS_MAX_INSET }
}

@Serializable
internal enum class UiIconFormat { PNG, WEBP, SVG }

@Serializable
internal data class UiIconAsset(val format: UiIconFormat, val payload: String) {
    val isValid: Boolean get() = payload.isNotBlank() && payload.length <= UI_ICON_MAX_PAYLOAD
}

@Serializable
internal enum class UiControlMotion { NONE, PRESS, BOUNCE, TILT }

@Serializable
internal enum class UiTextAlignment { DEFAULT, START, CENTER, END, JUSTIFY }

@Serializable
internal enum class UiLabelDisplay { ICON_AND_TEXT, ICON_ONLY, TEXT_ONLY }

@Serializable
internal enum class UiTabDirection { HORIZONTAL, VERTICAL }

@Serializable
internal data class UiControlStyle(
    val foreground: Int? = null,
    val background: Int? = null,
    val icon: UiIconAsset? = null,
    val motion: UiControlMotion = UiControlMotion.NONE,
    val textAlignment: UiTextAlignment = UiTextAlignment.DEFAULT,
    val labelDisplay: UiLabelDisplay = UiLabelDisplay.ICON_AND_TEXT,
    val tabDirection: UiTabDirection = UiTabDirection.HORIZONTAL,
) {
    val isValid: Boolean get() = listOfNotNull(foreground, background).all { it in 0..0xffffff } && icon?.isValid != false
}

@Serializable
internal data class UiCanvasItem(
    val id: String,
    val bounds: UiCanvasBounds,
    val padding: UiCanvasPadding = UiCanvasPadding(),
    val style: UiControlStyle = UiControlStyle(),
) {
    val isValid: Boolean get() = validComponentId(id) && bounds.isValid && padding.isValid && style.isValid
}

@Serializable
internal enum class UiPlacementMode { OVERLAP, PUSH }

@Serializable
internal data class UiCanvasLayout(
    val items: List<UiCanvasItem>,
    val hiddenStock: Set<String>? = null,
    val groups: List<Set<String>> = emptyList(),
    val snapping: Boolean = true,
    val placement: UiPlacementMode = UiPlacementMode.OVERLAP,
    val labelDisplays: Map<String, UiLabelDisplay> = emptyMap(),
) {
    val isValid: Boolean get() {
        val ids = items.map(UiCanvasItem::id).toSet()
        val hidden = hiddenStock.orEmpty()
        val grouped = groups.flatten()
        return items.size <= UI_CANVAS_MAX_ITEMS && items.all(UiCanvasItem::isValid) && ids.size == items.size &&
            hidden.size <= UI_CANVAS_MAX_ITEMS && hidden.all(::validComponentId) && ids.none { it in hidden } &&
            groups.all { it.size >= 2 } && grouped.size <= UI_CANVAS_MAX_ITEMS && grouped.distinct().size == grouped.size &&
            grouped.all { it !in hidden && (it in ids || hiddenStock != null && validComponentId(it)) } &&
            labelDisplays.size <= UI_CANVAS_MAX_ITEMS && labelDisplays.keys.all(::validComponentId) &&
            (placement == UiPlacementMode.OVERLAP || !items.hasOverlappingBounds())
    }

    val hasAdvancedOptions: Boolean get() = groups.isNotEmpty() || !snapping || placement != UiPlacementMode.OVERLAP ||
        items.any { it.style.textAlignment != UiTextAlignment.DEFAULT }

    /** Save overrides relative to the actual stock view. Untouched components stay in their native layouts. */
    fun stockOverrides(initial: UiCanvasLayout, previous: UiCanvasLayout?, stockIds: Set<String>): UiCanvasLayout? {
        val initialById = initial.items.associateBy(UiCanvasItem::id)
        val beforeOrder = initial.items.mapIndexed { index, item -> item.id to index }.toMap()
        val previouslyChanged = previous?.items.orEmpty().map(UiCanvasItem::id).toSet()
        val nowVisible = items.map(UiCanvasItem::id).toSet()
        val reordered = mutableSetOf<String>()
        items.forEachIndexed { index, item ->
            val originalIndex = beforeOrder[item.id] ?: initial.items.size
            items.subList(0, index).forEach { earlier ->
                if ((beforeOrder[earlier.id] ?: initial.items.size) > originalIndex) {
                    reordered += item.id
                    reordered += earlier.id
                }
            }
        }
        val overrides = items.filter { it.id in previouslyChanged || it.id in reordered || initialById[it.id] != it }
        val hidden = (previous?.hiddenStock.orEmpty() + hiddenStock.orEmpty() + (initialById.keys intersect stockIds)) - nowVisible
        val available = (nowVisible + stockIds) - hidden
        val saved = copy(items = overrides, hiddenStock = hidden, groups = groups.mapNotNull { group ->
            (group intersect available).takeIf { it.size >= 2 }
        })
        return saved.takeIf { overrides.isNotEmpty() || hidden.isNotEmpty() || it.hasAdvancedOptions || it.labelDisplays.isNotEmpty() }
    }

    fun add(item: UiCanvasItem): UiCanvasLayout {
        if (!item.isValid || items.size >= UI_CANVAS_MAX_ITEMS || items.any { it.id == item.id }) return this
        val updated = copy(items = items + item, hiddenStock = hiddenStock?.minus(item.id))
        return updated.resolvePlacement(updated.items, setOf(item.id), dx = 0, dy = 0) ?: this
    }

    fun members(id: String): Set<String> = groups.firstOrNull { id in it } ?: setOf(id)

    fun group(ids: Set<String>): UiCanvasLayout {
        val known = items.map(UiCanvasItem::id).toSet() + groups.flatten()
        if (ids.size < 2 || !known.containsAll(ids)) return this
        val selected = ids.flatMap(::members).toSet()
        val updated = groups.filterNot { group -> group.any { it in selected } } + listOf(selected)
        return if (updated.sumOf { it.size } <= UI_CANVAS_MAX_ITEMS) copy(groups = updated) else this
    }

    fun ungroup(id: String) = copy(groups = groups.filterNot { id in it })

    fun hide(id: String): UiCanvasLayout {
        if (items.none { it.id == id }) return this
        val selected = members(id)
        val unmeasured = selected - items.map(UiCanvasItem::id).toSet()
        return copy(
            items = items.filterNot { it.id in selected },
            groups = groups.filterNot { id in it },
            hiddenStock = if (unmeasured.isEmpty()) hiddenStock else hiddenStock.orEmpty() + unmeasured,
        )
    }

    fun boundsOf(ids: Set<String>): UiCanvasBounds? {
        val selected = items.filter { it.id in ids }
        return selected.takeIf { it.isNotEmpty() && it.size == ids.size }?.map(UiCanvasItem::bounds)?.canvasBounds()
    }

    fun move(id: String, dx: Int, dy: Int): UiCanvasLayout {
        val selected = members(id)
        val bounds = boundsOf(selected) ?: return this
        val moved = bounds.move(dx, dy)
        return reshape(selected, bounds, moved, moved.x - bounds.x, moved.y - bounds.y)
    }

    fun resize(id: String, dw: Int, dh: Int) = resize(id, UiResizeEdge.BOTTOM_RIGHT, dw, dh)

    fun resize(id: String, edge: UiResizeEdge, dx: Int, dy: Int): UiCanvasLayout {
        val selected = members(id)
        val bounds = boundsOf(selected) ?: return this
        val resized = bounds.resize(edge, dx, dy)
        val horizontalDelta = if (edge.horizontal < 0) resized.x - bounds.x else resized.right - bounds.right
        val verticalDelta = if (edge.vertical < 0) resized.y - bounds.y else resized.bottom - bounds.bottom
        return reshape(selected, bounds, resized, horizontalDelta, verticalDelta)
    }

    fun withPlacement(mode: UiPlacementMode): UiCanvasLayout {
        if (placement == mode) return this
        val updated = copy(placement = mode)
        return updated.resolvePlacement(items, emptySet(), dx = 0, dy = 0) ?: this
    }

    fun raise(id: String) = moveLayer(id, 1)

    fun lower(id: String) = moveLayer(id, -1)

    fun withPadding(id: String, padding: UiCanvasPadding) = if (padding.isValid) change(id) { it.copy(padding = padding) } else this

    fun withStyle(id: String, style: UiControlStyle) = if (style.isValid) change(id) { it.copy(style = style) } else this

    fun withLabelDisplay(id: String, display: UiLabelDisplay): UiCanvasLayout = copy(
        labelDisplays = if (display == UiLabelDisplay.ICON_AND_TEXT) labelDisplays - id else labelDisplays + (id to display),
    )

    fun snap(id: String): UiCanvasLayout {
        if (!snapping) return this
        val target = boundsOf(members(id)) ?: return this
        val (horizontal, vertical) = guides(id)
        val dx = alignmentOffset(target.horizontalAnchors, horizontal, -target.x..(UI_CANVAS_UNITS - target.right))
        val dy = alignmentOffset(target.verticalAnchors, vertical, -target.y..(UI_CANVAS_UNITS - target.bottom))
        return move(id, dx, dy)
    }

    fun snapResize(id: String, edge: UiResizeEdge): UiCanvasLayout {
        if (!snapping) return this
        val target = boundsOf(members(id)) ?: return this
        val (horizontal, vertical) = guides(id)
        return resize(id, edge,
            resizeAlignmentOffset(edge.horizontal, horizontal, target.x, target.right),
            resizeAlignmentOffset(edge.vertical, vertical, target.y, target.bottom))
    }

    fun guides(id: String): Pair<List<Int>, List<Int>> {
        if (!snapping) return emptyList<Int>() to emptyList()
        val selected = members(id)
        val others = items.filterNot { it.id in selected }.map(UiCanvasItem::bounds)
        val panelEdges = listOf(0, UI_CANVAS_UNITS / 2, UI_CANVAS_UNITS)
        return (panelEdges + others.flatMap(UiCanvasBounds::horizontalAnchors)).distinct() to
            (panelEdges + others.flatMap(UiCanvasBounds::verticalAnchors)).distinct()
    }

    private fun reshape(
        selected: Set<String>, original: UiCanvasBounds, resized: UiCanvasBounds, dx: Int, dy: Int,
    ): UiCanvasLayout {
        if (original == resized) return this
        val updated = items.map { item ->
            if (item.id in selected) item.copy(bounds = item.bounds.scaleWithin(original, resized)) else item
        }
        if (updated.any { !it.bounds.isValid }) return this
        return resolvePlacement(updated, selected, dx, dy) ?: this
    }

    private fun resolvePlacement(updated: List<UiCanvasItem>, selected: Set<String>, dx: Int, dy: Int): UiCanvasLayout? =
        if (placement == UiPlacementMode.OVERLAP || !updated.hasOverlappingBounds()) copy(items = updated)
        else pushCanvasItems(updated, groups, selected, dx, dy)?.let { copy(items = it) }

    private fun moveLayer(id: String, step: Int): UiCanvasLayout {
        if (items.none { it.id == id }) return this
        val selected = members(id)
        val boundary = if (step > 0) items.indexOfLast { it.id in selected } else items.indexOfFirst { it.id in selected }
        val siblingIds = items.getOrNull(boundary + step)?.let { members(it.id) }
        val rest = items.filterNot { it.id in selected }
        val insertAt = when {
            siblingIds == null -> if (step > 0) rest.size else 0
            step > 0 -> rest.indexOfLast { it.id in siblingIds } + 1
            else -> rest.indexOfFirst { it.id in siblingIds }
        }
        return copy(items = rest.toMutableList().apply { addAll(insertAt, items.filter { it.id in selected }) })
    }

    private fun change(id: String, update: (UiCanvasItem) -> UiCanvasItem) =
        copy(items = items.map { if (it.id == id) update(it) else it })
}

internal fun validComponentId(id: String) = id.length <= 80 && componentId.matches(id)

@Serializable
internal data class UiThemeCanvases(
    val panels: Map<UiSurface, UiCanvasLayout> = emptyMap(),
    val gestures: UiGestureProfile = UiGestureProfile(),
) {
    val isEmpty: Boolean get() = panels.isEmpty() && gestures.isEmpty

    val hasWorkspaceOptions: Boolean get() = UiSurface.LIBRARY_WORKSPACE in panels || panels.values.any { panel ->
        panel.labelDisplays.isNotEmpty() ||
            panel.items.any { it.style.labelDisplay != UiLabelDisplay.ICON_AND_TEXT || it.style.tabDirection != UiTabDirection.HORIZONTAL }
    }

    val isValid: Boolean get() = panels.values.all(UiCanvasLayout::isValid) &&
        panels.values.sumOf { panel -> panel.items.sumOf { it.style.icon?.payload?.length ?: 0 } } <= THEME_ICON_BUDGET &&
        gestures.isValid && (gestures.isEmpty || Json.encodeToString(gestures).encodeToByteArray().size <= THEME_GESTURE_BUDGET)
}

internal data class UiCanvasState(val themeId: String, val layouts: UiThemeCanvases)

internal class UiCanvasSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val state = file.data.map { preferences ->
        UiCanvasState(AppearanceSettings.panelThemeId(preferences), layoutsOf(preferences))
    }.distinctUntilChanged()

    suspend fun save(themeId: String, surface: UiSurface, layout: UiCanvasLayout?): Boolean =
        update(themeId, surface) { layout }

    suspend fun saveGestures(
        themeId: String,
        surface: UiSurface,
        layout: UiCanvasLayout?,
        profile: UiGestureProfile,
        environment: UiGestureEnvironment,
        expected: UiGestureProfile? = null,
    ): Boolean = apply(themeId) { before ->
        if (expected != null && before.gestures != expected) return@apply null
        val oldPresets = before.gestures.presets.associateBy(UiGesturePreset::id)
        val newPresets = profile.presets.associateBy(UiGesturePreset::id)
        val referencesElsewhere = before.gestures.surfaces.filterKeys { it != surface }.values
            .flatMap(UiSurfaceGestures::slots).mapNotNull { it.gesture.presetId }
        if (referencesElsewhere.any { oldPresets[it]?.points != newPresets[it]?.points }) return@apply null
        val draft = before.gestures.copy(presets = profile.presets)
            .editSurface(surface, profile.surfaces[surface] ?: UiSurfaceGestures())
        if (draft.validate(surface, environment, before.gestures) != null) return@apply null
        before.withPanel(surface, layout).copy(gestures = draft.prune(surface, environment, before.gestures))
    }

    suspend fun setLabelDisplay(themeId: String, surface: UiSurface, id: String, display: UiLabelDisplay): Boolean =
        update(themeId, surface) { old ->
            val layout = old ?: UiCanvasLayout(emptyList(), hiddenStock = emptySet())
            val itemStyle = layout.items.firstOrNull { it.id == id }?.style
            val labels = layout.withLabelDisplay(id, display)
            val changed = if (itemStyle == null) labels else labels.withStyle(id, itemStyle.copy(labelDisplay = UiLabelDisplay.ICON_AND_TEXT))
            changed.takeUnless { old == null && it.labelDisplays.isEmpty() }
        }

    private suspend fun update(
        themeId: String,
        surface: UiSurface,
        change: (UiCanvasLayout?) -> UiCanvasLayout?,
    ): Boolean = apply(themeId) { before -> before.withPanel(surface, change(before.panels[surface])) }

    private suspend fun apply(themeId: String, change: (UiThemeCanvases) -> UiThemeCanvases?): Boolean {
        var applied = false
        val saved = SettingsFiles.edit(file) { preferences ->
            if (AppearanceSettings.panelThemeId(preferences) == themeId) {
                val updated = change(layoutsOf(preferences))
                if (updated?.isValid == true &&
                    CustomTabsSettings.definitionsOf(preferences).keys.containsAll(updated.referencedCustomTabIds())
                ) {
                    store(preferences, themeId, updated)
                    applied = true
                }
            }
        }
        return saved != null && applied
    }

    private fun UiThemeCanvases.withPanel(surface: UiSurface, layout: UiCanvasLayout?) =
        copy(panels = if (layout == null) panels - surface else panels + (surface to layout))

    companion object {
        private const val CANVAS_PREFIX = "ui_canvas_"

        fun layoutsOf(preferences: Preferences): UiThemeCanvases =
            savedLayouts(preferences, AppearanceSettings.panelThemeId(preferences))

        fun savedLayouts(preferences: Preferences, themeId: String): UiThemeCanvases =
            validatedLayouts(preferences, themeId) ?: UiThemeCanvases()

        /** Null identifies an invalid stored unit, distinct from a theme without overrides. */
        fun validatedLayouts(preferences: Preferences, themeId: String): UiThemeCanvases? {
            val stored = preferences[key(themeId)] ?: return UiThemeCanvases()
            return try {
                Json.decodeFromString<UiThemeCanvases>(stored).takeIf(UiThemeCanvases::isValid)
            } catch (_: SerializationException) {
                null
            }
        }

        fun store(preferences: MutablePreferences, themeId: String, canvases: UiThemeCanvases) {
            if (canvases.isEmpty) preferences.remove(key(themeId))
            else preferences[key(themeId)] = Json.encodeToString(canvases)
        }

        fun key(themeId: String) = stringPreferencesKey("$CANVAS_PREFIX$themeId")

        fun themeForKey(key: String): String? = key.takeIf { it.startsWith(CANVAS_PREFIX) }?.removePrefix(CANVAS_PREFIX)
    }
}
