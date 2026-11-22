package io.github.lorddetson.piplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Geometry is in thousandths of the safe panel area. Insets are logical dp. */
internal const val UI_CANVAS_UNITS = 1_000
internal const val UI_CANVAS_MIN_SIZE = 20
internal const val UI_CANVAS_MAX_INSET = 48
internal const val UI_CANVAS_MAX_ITEMS = 32
internal const val UI_ICON_MAX_PAYLOAD = 14_000
private const val ALIGNMENT_TOLERANCE = 9
private const val THEME_ICON_BUDGET = 192_000
private val componentId = Regex("[a-z][a-z0-9_.-]*:[a-z0-9_.-]+")

@Serializable
internal enum class UiSurface { LIBRARY, NOW_PLAYING, MINI_PLAYER, BOTTOM_NAVIGATION }

@Serializable
internal data class UiCanvasBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val isValid: Boolean get() = width in UI_CANVAS_MIN_SIZE..UI_CANVAS_UNITS && height in UI_CANVAS_MIN_SIZE..UI_CANVAS_UNITS &&
        x in 0..(UI_CANVAS_UNITS - width) && y in 0..(UI_CANVAS_UNITS - height)
    val horizontalAnchors: List<Int> get() = listOf(x, x + width / 2, x + width)
    val verticalAnchors: List<Int> get() = listOf(y, y + height / 2, y + height)

    fun move(dx: Int, dy: Int) = copy(x = (x + dx).coerceIn(0, UI_CANVAS_UNITS - width), y = (y + dy).coerceIn(0, UI_CANVAS_UNITS - height))

    fun resize(dw: Int, dh: Int) = copy(
        width = (width + dw).coerceIn(UI_CANVAS_MIN_SIZE, UI_CANVAS_UNITS - x),
        height = (height + dh).coerceIn(UI_CANVAS_MIN_SIZE, UI_CANVAS_UNITS - y),
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
internal data class UiControlStyle(
    val foreground: Int? = null,
    val background: Int? = null,
    val icon: UiIconAsset? = null,
    val motion: UiControlMotion = UiControlMotion.NONE,
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
    val isValid: Boolean get() = id.length <= 80 && componentId.matches(id) && bounds.isValid && padding.isValid && style.isValid
}

@Serializable
internal data class UiCanvasLayout(val items: List<UiCanvasItem>, val hiddenStock: Set<String>? = null) {
    val isValid: Boolean get() = items.size <= UI_CANVAS_MAX_ITEMS && items.all(UiCanvasItem::isValid) &&
        items.map(UiCanvasItem::id).distinct().size == items.size &&
        hiddenStock?.let { hidden ->
            hidden.size <= UI_CANVAS_MAX_ITEMS && hidden.all { id -> id.length <= 80 && componentId.matches(id) } &&
                items.none { it.id in hidden }
        } != false

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
        val hidden = (previous?.hiddenStock.orEmpty() + (initialById.keys intersect stockIds)) - nowVisible
        return if (overrides.isEmpty() && hidden.isEmpty()) null else UiCanvasLayout(overrides, hidden)
    }

    fun add(item: UiCanvasItem): UiCanvasLayout =
        if (!item.isValid || items.size >= UI_CANVAS_MAX_ITEMS || items.any { it.id == item.id }) this else copy(items = items + item)

    fun hide(id: String) = copy(items = items.filterNot { it.id == id })

    fun move(id: String, dx: Int, dy: Int) = change(id) { it.copy(bounds = it.bounds.move(dx, dy)) }

    fun resize(id: String, dw: Int, dh: Int) = change(id) { it.copy(bounds = it.bounds.resize(dw, dh)) }

    fun raise(id: String) = moveLayer(id, 1)

    fun lower(id: String) = moveLayer(id, -1)

    fun withPadding(id: String, padding: UiCanvasPadding) = if (padding.isValid) change(id) { it.copy(padding = padding) } else this

    fun withStyle(id: String, style: UiControlStyle) = if (style.isValid) change(id) { it.copy(style = style) } else this

    fun snap(id: String): UiCanvasLayout {
        val target = items.firstOrNull { it.id == id }?.bounds ?: return this
        val (horizontal, vertical) = guides(id)
        val dx = alignmentOffset(target.horizontalAnchors, horizontal, target.x, target.width)
        val dy = alignmentOffset(target.verticalAnchors, vertical, target.y, target.height)
        return move(id, dx, dy)
    }

    fun guides(id: String): Pair<List<Int>, List<Int>> {
        val others = items.filterNot { it.id == id }.map(UiCanvasItem::bounds)
        val panelEdges = listOf(0, UI_CANVAS_UNITS / 2, UI_CANVAS_UNITS)
        return (panelEdges + others.flatMap(UiCanvasBounds::horizontalAnchors)).distinct() to
            (panelEdges + others.flatMap(UiCanvasBounds::verticalAnchors)).distinct()
    }

    private fun alignmentOffset(edges: List<Int>, guides: List<Int>, start: Int, size: Int): Int =
        edges.flatMap { edge -> guides.map { it - edge } }.asSequence()
            .filter { abs(it) <= ALIGNMENT_TOLERANCE && start + it in 0..(UI_CANVAS_UNITS - size) }
            .minByOrNull(::abs) ?: 0

    private fun moveLayer(id: String, step: Int): UiCanvasLayout {
        val from = items.indexOfFirst { it.id == id }
        val to = from + step
        if (from < 0 || to !in items.indices) return this
        return copy(items = items.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun change(id: String, update: (UiCanvasItem) -> UiCanvasItem) =
        copy(items = items.map { if (it.id == id) update(it) else it })
}

@Serializable
internal data class UiThemeCanvases(val panels: Map<UiSurface, UiCanvasLayout> = emptyMap()) {
    val isValid: Boolean get() = panels.values.all(UiCanvasLayout::isValid) &&
        panels.values.sumOf { panel -> panel.items.sumOf { it.style.icon?.payload?.length ?: 0 } } <= THEME_ICON_BUDGET
}

internal data class UiCanvasState(val themeId: String, val layouts: UiThemeCanvases)

internal class UiCanvasSettings(private val file: DataStore<Preferences>) {
    constructor(context: Context) : this(SettingsFiles.settings(context))

    val state = file.data.map { preferences ->
        UiCanvasState(AppearanceSettings.panelThemeId(preferences), layoutsOf(preferences))
    }.distinctUntilChanged()

    suspend fun save(themeId: String, surface: UiSurface, layout: UiCanvasLayout?): Boolean {
        if (layout?.isValid == false) return false
        var applied = false
        val saved = SettingsFiles.edit(file) { preferences ->
            if (AppearanceSettings.panelThemeId(preferences) == themeId) {
                val panels = layoutsOf(preferences).panels
                val updated = UiThemeCanvases(if (layout == null) panels - surface else panels + (surface to layout))
                if (updated.isValid) {
                    store(preferences, themeId, updated)
                    applied = true
                }
            }
        }
        return saved != null && applied
    }

    companion object {
        fun layoutsOf(preferences: Preferences): UiThemeCanvases = try {
            val stored = preferences[key(AppearanceSettings.panelThemeId(preferences))]
            stored?.let { Json.decodeFromString<UiThemeCanvases>(it) }?.takeIf(UiThemeCanvases::isValid) ?: UiThemeCanvases()
        } catch (_: SerializationException) {
            UiThemeCanvases()
        }

        fun store(preferences: MutablePreferences, themeId: String, canvases: UiThemeCanvases) {
            if (canvases.panels.isEmpty()) preferences.remove(key(themeId))
            else preferences[key(themeId)] = Json.encodeToString(canvases)
        }

        private fun key(themeId: String) = stringPreferencesKey("ui_canvas_$themeId")
    }
}
