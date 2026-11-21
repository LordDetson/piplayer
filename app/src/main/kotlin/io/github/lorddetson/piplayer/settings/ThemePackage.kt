package io.github.lorddetson.piplayer.settings

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
)

internal sealed interface ThemePackageRead {
    data class Loaded(val theme: ThemePackage) : ThemePackageRead
    data object Invalid : ThemePackageRead
    data object UnsupportedVersion : ThemePackageRead
    data object TooLarge : ThemePackageRead
    data object Unreadable : ThemePackageRead
}

internal object ThemePackageCodec {
    const val MAX_BYTES = 32 * 1024
    private const val PALETTE_VERSION = 1
    private const val PANEL_VERSION = 2
    private const val PLAYER_VERSION = 3
    private const val MAX_NAME_LENGTH = 80

    fun encode(theme: ThemePackage): ByteArray {
        require(validName(theme.name) && theme.palette.hasValidColors)
        val panel = theme.panel?.let(LibraryPanelDocument::from)
        require(panel == null || panel.toLayout() == theme.panel)
        val player = theme.player?.let(NowPlayingPanelDocument::from)
        require(player == null || player.toPanel() == theme.player)
        val version = when {
            player != null -> PLAYER_VERSION
            panel != null -> PANEL_VERSION
            else -> PALETTE_VERSION
        }
        return Json.encodeToString(
            Document(version, theme.name, theme.base.stored, theme.palette, theme.layout?.stored, panel, player),
        ).encodeToByteArray()
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
            if (version !in PALETTE_VERSION..PLAYER_VERSION) return ThemePackageRead.UnsupportedVersion
            if (("player" in parsed) != (version == PLAYER_VERSION)) return ThemePackageRead.Invalid
            if (version != PLAYER_VERSION && ("panel" in parsed) != (version == PANEL_VERSION)) return ThemePackageRead.Invalid

            val palette = parsed["palette"] as? JsonObject
            if (palette?.values?.any { it is JsonPrimitive && it.isString } == true) return ThemePackageRead.Invalid
            val document = Json.decodeFromJsonElement<Document>(parsed)
            val base = ThemeMode.entries.firstOrNull { it.stored == document.base } ?: return ThemePackageRead.Invalid
            val layout = document.layout?.let { id ->
                NowPlayingLayout.entries.firstOrNull { it.stored == id } ?: return ThemePackageRead.Invalid
            }
            if (!validName(document.name) || !document.palette.hasValidColors) return ThemePackageRead.Invalid
            val panel = if ("panel" in parsed) document.panel?.toLayout() ?: return ThemePackageRead.Invalid else null
            val player = if (version == PLAYER_VERSION) document.player?.toPanel() ?: return ThemePackageRead.Invalid else null
            ThemePackageRead.Loaded(ThemePackage(document.name, base, document.palette, layout, panel, player))
        } catch (_: CharacterCodingException) {
            ThemePackageRead.Invalid
        } catch (_: SerializationException) {
            ThemePackageRead.Invalid
        }
    }

    private fun validName(name: String): Boolean = name.length <= MAX_NAME_LENGTH && name.isNotBlank() &&
        name.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(name)

    /** The palette and panel are flat objects. Bound nesting before asking the JSON parser to build a tree. */
    private fun hasBoundedStructure(text: String): Boolean {
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
                    '{' -> if (++depth > 2) return false
                    '}' -> if (--depth < 0) return false
                    '[', ']' -> return false
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
    )
}
