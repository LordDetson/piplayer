package io.github.lorddetson.piplayer.settings

import io.github.lorddetson.piplayer.library.customtabs.isCustomTabId
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelDocument
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelLayout
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

internal data class ThemePackage(
    val name: String,
    val base: ThemeMode,
    val palette: ThemePalette = ThemePalette(),
    val layout: NowPlayingLayout? = null,
    val panel: LibraryPanelLayout? = null,
    val player: NowPlayingPanel? = null,
    val miniPlayer: MiniPlayerPanel? = null,
    val navigation: BottomNavigationLayout? = null,
    val canvases: UiThemeCanvases? = null,
)

internal sealed interface ThemePackageRead {
    data class Loaded(val theme: ThemePackage) : ThemePackageRead
    data object Invalid : ThemePackageRead
    data object UnsupportedVersion : ThemePackageRead
    data object TooLarge : ThemePackageRead
    data object Unreadable : ThemePackageRead
}

internal object ThemePackageCodec {
    const val MAX_BYTES = 512 * 1024
    const val LEGACY_MAX_BYTES = 32 * 1024
    private const val PALETTE_VERSION = 1
    private const val PANEL_VERSION = 2
    private const val PLAYER_VERSION = 3
    private const val MINI_PLAYER_VERSION = 4
    private const val NAVIGATION_VERSION = 5
    private const val CANVAS_VERSION = 6
    private const val ANCHORED_CANVAS_VERSION = 7
    private const val ADVANCED_CANVAS_VERSION = 8
    private const val WORKSPACE_VERSION = 9
    private const val GESTURE_VERSION = 10
    private const val MAX_NAME_LENGTH = 80

    fun encode(theme: ThemePackage): ByteArray {
        require(validName(theme.name) && theme.palette.hasValidColors)
        val defaultPanel = LibraryPanelLayout()
        val projection = theme.canvases?.withoutCustomTabs(theme.panel ?: defaultPanel, ::isCustomTabId)
        val stockPanel = projection?.panel?.takeUnless { theme.panel == null && it == defaultPanel }
            ?: theme.panel?.withoutTabs(::isCustomTabId)
        val stockCanvases = projection?.canvases
        val panel = stockPanel?.let(LibraryPanelDocument::from)
        require(panel == null || panel.toLayout() == stockPanel)
        val player = theme.player?.let(NowPlayingPanelDocument::from)
        require(player == null || player.toPanel() == theme.player)
        val miniPlayer = theme.miniPlayer?.let(MiniPlayerPanelDocument::from)
        require(miniPlayer == null || miniPlayer.toPanel() == theme.miniPlayer)
        val navigation = theme.navigation?.let(BottomNavigationDocument::from)
        require(navigation == null || navigation.toLayout() == theme.navigation)
        require(stockCanvases?.isValid != false)
        val version = when {
            stockCanvases?.gestures?.isEmpty == false -> GESTURE_VERSION
            stockCanvases?.hasWorkspaceOptions == true -> WORKSPACE_VERSION
            stockCanvases?.panels?.values?.any(UiCanvasLayout::hasAdvancedOptions) == true -> ADVANCED_CANVAS_VERSION
            stockCanvases?.panels?.values?.any { it.hiddenStock != null } == true -> ANCHORED_CANVAS_VERSION
            stockCanvases != null -> CANVAS_VERSION
            navigation != null -> NAVIGATION_VERSION
            miniPlayer != null -> MINI_PLAYER_VERSION
            player != null -> PLAYER_VERSION
            panel != null -> PANEL_VERSION
            else -> PALETTE_VERSION
        }
        return Json.encodeToString(
            Document(version, theme.name, theme.base.stored, theme.palette, theme.layout?.stored, panel, player, miniPlayer, navigation, stockCanvases),
        ).encodeToByteArray().also { require(it.size <= MAX_BYTES) }
    }

    /** The caller owns the stream. The byte limit also applies to providers with no reported file size. */
    fun decode(source: InputStream): ThemePackageRead {
        val buffer = ByteArray(MAX_BYTES + 1)
        var length = 0
        try {
            while (length < buffer.size) {
                val count = source.read(buffer, length, buffer.size - length)
                if (count < 0) break
                length += count
            }
        } catch (_: IOException) {
            return ThemePackageRead.Unreadable
        }
        if (length > MAX_BYTES) return ThemePackageRead.TooLarge

        return try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer, 0, length)).toString()
            if (!hasBoundedStructure(text)) return ThemePackageRead.Invalid
            val parsed = Json.parseToJsonElement(text) as? JsonObject ?: return ThemePackageRead.Invalid
            val version = (parsed["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return ThemePackageRead.Invalid
            if (version !in PALETTE_VERSION..GESTURE_VERSION) return ThemePackageRead.UnsupportedVersion
            if (version < CANVAS_VERSION && length > LEGACY_MAX_BYTES) return ThemePackageRead.TooLarge
            if (version < CANVAS_VERSION && !hasBoundedStructure(text, maxDepth = 2, allowArrays = false)) return ThemePackageRead.Invalid
            if (version in CANVAS_VERSION until GESTURE_VERSION && !hasBoundedStructure(text, maxDepth = 9)) return ThemePackageRead.Invalid
            if (!hasVersionFields(version, parsed)) return ThemePackageRead.Invalid

            val palette = parsed["palette"] as? JsonObject
            if (palette?.values?.any { it is JsonPrimitive && it.isString } == true) return ThemePackageRead.Invalid
            val document = Json.decodeFromJsonElement<Document>(parsed)
            val base = ThemeMode.entries.firstOrNull { it.stored == document.base } ?: return ThemePackageRead.Invalid
            val layout = document.layout?.let { id ->
                NowPlayingLayout.entries.firstOrNull { it.stored == id } ?: return ThemePackageRead.Invalid
            }
            if (!validName(document.name) || !document.palette.hasValidColors) return ThemePackageRead.Invalid
            val panel = if ("panel" in parsed) document.panel?.toLayout() ?: return ThemePackageRead.Invalid else null
            val player = if ("player" in parsed) document.player?.toPanel() ?: return ThemePackageRead.Invalid else null
            val miniPlayer = if ("miniPlayer" in parsed) document.miniPlayer?.toPanel() ?: return ThemePackageRead.Invalid else null
            val navigation = if ("navigation" in parsed) document.navigation?.toLayout() ?: return ThemePackageRead.Invalid else null
            val canvases = if ("canvases" in parsed) {
                document.canvases?.takeIf(UiThemeCanvases::isValid) ?: return ThemePackageRead.Invalid
            } else null
            if (canvases?.referencedCustomTabIds()?.isNotEmpty() == true) return ThemePackageRead.Invalid
            val hasAnchors = canvases?.panels?.values?.any { it.hiddenStock != null } == true
            val hasAdvancedOptions = canvases?.panels?.values?.any(UiCanvasLayout::hasAdvancedOptions) == true
            if (version < ADVANCED_CANVAS_VERSION && (version == ANCHORED_CANVAS_VERSION) != hasAnchors) return ThemePackageRead.Invalid
            if (version < WORKSPACE_VERSION && (version == ADVANCED_CANVAS_VERSION) != hasAdvancedOptions) return ThemePackageRead.Invalid
            if (version < GESTURE_VERSION && (version == WORKSPACE_VERSION) != (canvases?.hasWorkspaceOptions == true)) {
                return ThemePackageRead.Invalid
            }
            val hasGestureField = (parsed["canvases"] as? JsonObject)?.containsKey("gestures") == true
            if ((version == GESTURE_VERSION) != hasGestureField ||
                (version == GESTURE_VERSION) != (canvases?.gestures?.isEmpty == false)
            ) return ThemePackageRead.Invalid
            if (canvases != null && hidesLibraryGestureReplacement(canvases, panel)) return ThemePackageRead.Invalid
            ThemePackageRead.Loaded(ThemePackage(document.name, base, document.palette, layout, panel, player, miniPlayer, navigation, canvases))
        } catch (_: CharacterCodingException) {
            ThemePackageRead.Invalid
        } catch (_: SerializationException) {
            ThemePackageRead.Invalid
        }
    }

    private fun validName(name: String): Boolean = name.length <= MAX_NAME_LENGTH && name.isNotBlank() &&
        name.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(name)

    /** The workspace can hide a whole library strip before its gestures are mounted. */
    internal fun hidesLibraryGestureReplacement(canvases: UiThemeCanvases, legacy: LibraryPanelLayout?): Boolean {
        val workspace = canvases.panels[UiSurface.LIBRARY_WORKSPACE] ?: return false
        val library = canvases.gestures.surfaces[UiSurface.LIBRARY] ?: return false
        val placed = workspace.items.mapTo(mutableSetOf(), UiCanvasItem::id)
        val hidden = workspace.hiddenStock
        val child = canvases.panels[UiSurface.LIBRARY]
        val childPlaced = child?.items.orEmpty().mapTo(mutableSetOf(), UiCanvasItem::id)
        fun isVisible(component: String): Boolean {
            val kind = component.substringBefore(':')
            val block = when (kind) {
                "tab" -> "workspace:tabs"
                "library" -> "workspace:toolbar"
                else -> return true
            }
            if (block !in placed && (hidden == null || block in hidden)) return false
            if (component in childPlaced) return true
            if (child != null && child.hiddenStock?.contains(component) != false) return false
            val legacyHidden = if (kind == "tab") legacy?.tabs?.hidden else legacy?.buttons?.hidden
            return component.substringAfter(':') !in legacyHidden.orEmpty()
        }
        val muted = library.unbound.mapNotNull { slot ->
            val component = slot.target.members.singleOrNull() ?: return@mapNotNull null
            val action = when (slot.gesture.builtIn) {
                UiBuiltInGesture.TAP -> UiGestureActionId.STOCK_CLICK
                UiBuiltInGesture.LONG_PRESS -> UiGestureActionId.CONTEXT_MENU
                else -> return@mapNotNull null
            }
            UiGestureBinding(slot, UiGestureAction(action, targetId = component))
        }
        val reassigned = library.bindings.mapNotNull { binding ->
            val component = binding.action.targetId ?: return@mapNotNull null
            val kind = when (binding.action.id) {
                UiGestureActionId.STOCK_CLICK -> UiBuiltInGesture.TAP
                UiGestureActionId.CONTEXT_MENU -> UiBuiltInGesture.LONG_PRESS
                else -> return@mapNotNull null
            }
            val slot = UiGestureSlot(UiGestureTarget.component(component), UiGesture.builtIn(kind))
            UiGestureBinding(slot, binding.action)
        }
        val stock = (muted + reassigned).distinctBy(UiGestureBinding::slot)
        val components = (library.bindings + stock).flatMapTo(mutableSetOf()) { it.slot.target.members }
        val visible = components.filterTo(mutableSetOf(), ::isVisible)
        if (visible == components) return false
        val groups = child?.groups.orEmpty()
        val environment = UiGestureEnvironment(visible, groups, stock)
        return canvases.gestures.validate(UiSurface.LIBRARY, environment) is UiGestureProblem.MissingActions
    }

    private fun hasVersionFields(version: Int, parsed: JsonObject): Boolean =
        listOf(PANEL_VERSION to "panel", PLAYER_VERSION to "player", MINI_PLAYER_VERSION to "miniPlayer",
            NAVIGATION_VERSION to "navigation", CANVAS_VERSION to "canvases")
            .all { (introduced, field) ->
                when {
                    version < introduced -> field !in parsed
                    version == introduced -> field in parsed
                    else -> true
                }
            }

    /** Bound nesting before asking the JSON parser to build a tree. */
    private fun hasBoundedStructure(text: String, maxDepth: Int = 12, allowArrays: Boolean = true): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> if ((!allowArrays && character == '[') || ++depth > maxDepth) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    @Serializable
    private data class Document(
        val version: Int,
        val name: String,
        val base: String,
        val palette: ThemePalette = ThemePalette(),
        val layout: String? = null,
        val panel: LibraryPanelDocument? = null,
        val player: NowPlayingPanelDocument? = null,
        val miniPlayer: MiniPlayerPanelDocument? = null,
        val navigation: BottomNavigationDocument? = null,
        val canvases: UiThemeCanvases? = null,
    )
}
