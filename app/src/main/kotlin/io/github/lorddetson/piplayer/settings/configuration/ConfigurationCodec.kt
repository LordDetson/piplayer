package io.github.lorddetson.piplayer.settings.configuration

import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinition
import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinitionDocument
import io.github.lorddetson.piplayer.library.customtabs.isCustomTabId
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.BottomNavigationDocument
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.MiniPlayerPanelDocument
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.NowPlayingPanelDocument
import io.github.lorddetson.piplayer.settings.ThemeFontSelection
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryPanelDocument
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal sealed interface ConfigurationRead {
    data class Loaded(val patch: ConfigurationPatch) : ConfigurationRead
    data object Invalid : ConfigurationRead
    data object UnsupportedVersion : ConfigurationRead
    data object TooLarge : ConfigurationRead
    data object Unreadable : ConfigurationRead
}

internal class ConfigurationTooLargeException : IllegalArgumentException()

internal object ConfigurationCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val BASE_VERSION = 1
    private const val CUSTOM_TABS_VERSION = 2
    private const val BUILT_IN = "built_in"

    private val languages = mapOf(AppLanguage.SYSTEM to "system", AppLanguage.ENGLISH to "en", AppLanguage.RUSSIAN to "ru")
    private val themeModes = ThemeMode.entries.associateWith(ThemeMode::stored)
    private val presentations = NowPlayingLayout.entries.associateWith(NowPlayingLayout::stored)
    private val listNames = ListName.entries.associateWith(ListName::id)
    private val id3v1Policies = mapOf(
        Id3v1WritePolicy.UPDATE_IF_PRESENT to "update_if_present",
        Id3v1WritePolicy.REMOVE to "remove",
        Id3v1WritePolicy.LEAVE_UNTOUCHED to "leave_untouched",
    )
    private val coverPresets = mapOf(
        TagCoverPreset.COMPATIBLE to "compatible",
        TagCoverPreset.CAR_STEREO to "car_stereo",
        TagCoverPreset.ORIGINAL to "original",
    )
    private val surfaces = mapOf(
        UiSurface.LIBRARY to "library",
        UiSurface.NOW_PLAYING to "now_playing",
        UiSurface.MINI_PLAYER to "mini_player",
        UiSurface.BOTTOM_NAVIGATION to "bottom_navigation",
        UiSurface.LIBRARY_WORKSPACE to "library_workspace",
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val formatted = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(patch: ConfigurationPatch): ByteArray {
        val document = orderedObject {
            val hasCustomTabs = patch.library.customTabs.isNotEmpty() || patch.library.customTabOrder != null ||
                patch.appearance.referencedCustomTabIds().isNotEmpty()
            add("version", if (hasCustomTabs) CUSTOM_TABS_VERSION else BASE_VERSION)
            add("language", patch.language?.let(languages::getValue))
            addObject("library", libraryJson(patch.library))
            addObject("tagWriting", orderedObject {
                add("id3v1", patch.tagWriting.id3v1?.let(id3v1Policies::getValue))
                add("cover", patch.tagWriting.cover?.let(coverPresets::getValue))
            })
            addObject("sleepTimer", orderedObject {
                add("hours", patch.sleepTimer.hours)
                add("minutes", patch.sleepTimer.minutes)
                add("finishTrack", patch.sleepTimer.finishTrack)
            })
            addObject("appearance", appearanceJson(patch.appearance))
        }
        val result = formatted.encodeToString(document) + "\n"
        require(Charsets.UTF_8.newEncoder().canEncode(result))
        return result.encodeToByteArray().also { if (it.size > MAX_BYTES) throw ConfigurationTooLargeException() }
    }

    /** The caller owns the stream. Its reported size is unnecessary. */
    fun decode(source: InputStream): ConfigurationRead {
        val bytes = ByteArray(MAX_BYTES + 1)
        val length = try {
            readBounded(source, bytes)
        } catch (_: IOException) {
            return ConfigurationRead.Unreadable
        } catch (_: SecurityException) {
            return ConfigurationRead.Unreadable
        }
        if (length > MAX_BYTES) return ConfigurationRead.TooLarge
        return try {
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString()
            val document = ConfigurationJsonReader(text).read()
            val version = document["version"]?.documentInt() ?: return ConfigurationRead.Invalid
            if (version !in BASE_VERSION..CUSTOM_TABS_VERSION) return ConfigurationRead.UnsupportedVersion
            ConfigurationRead.Loaded(readPatch(document, allowCustomTabs = version >= CUSTOM_TABS_VERSION))
        } catch (_: CharacterCodingException) {
            ConfigurationRead.Invalid
        } catch (_: IllegalArgumentException) {
            ConfigurationRead.Invalid
        }
    }

    private fun readBounded(source: InputStream, bytes: ByteArray): Int {
        var length = 0
        while (length < bytes.size) {
            val count = source.read(bytes, length, bytes.size - length)
            if (count < 0) break
            if (count > 0) {
                length += count
            } else {
                val byte = source.read()
                if (byte < 0) break
                bytes[length++] = byte.toByte()
            }
        }
        return length
    }

    private fun readPatch(value: JsonElement, allowCustomTabs: Boolean): ConfigurationPatch {
        val root = value.documentObject("version", "language", "library", "tagWriting", "sleepTimer", "appearance")
        val writing = root["tagWriting"]?.documentObject("id3v1", "cover").orEmpty()
        val timer = root["sleepTimer"]?.documentObject("hours", "minutes", "finishTrack").orEmpty()
        val library = root["library"]?.let { readLibrary(it, allowCustomTabs) } ?: LibraryPatch()
        val patch = ConfigurationPatch(
            language = root["language"]?.let { languages.knownId(it.documentString()) },
            library = library,
            tagWriting = TagWritingPatch(
                id3v1 = writing["id3v1"]?.let { id3v1Policies.knownId(it.documentString()) },
                cover = writing["cover"]?.let { coverPresets.knownId(it.documentString()) },
            ),
            sleepTimer = SleepTimerPatch(
                hours = timer["hours"]?.documentInt()?.also { require(it in 0..23) },
                minutes = timer["minutes"]?.documentInt()?.also { require(it in 0..59) },
                finishTrack = timer["finishTrack"]?.documentBoolean(),
            ),
            appearance = root["appearance"]?.let { readAppearance(it, library.customTabs.keys.takeIf { allowCustomTabs }) }
                ?: AppearancePatch(),
        )
        require(allowCustomTabs || patch.appearance.referencedCustomTabIds().isEmpty())
        return patch
    }

    private fun libraryJson(patch: LibraryPatch): JsonObject = orderedObject {
        add("showSystemSounds", patch.showSystemSounds)
        add("showRecordings", patch.showRecordings)
        add("minLengthSeconds", patch.minLengthSeconds)
        add("legacyEncodingId", patch.legacyEncodingId)
        addObject("lists", orderedObject {
            for ((list, change) in patch.lists) put(list.id, orderedObject {
                add("field", change.field)
                add("reversed", change.reversed)
                add("grid", change.grid)
            })
        })
        addObject("customTabs", orderedObject {
            for ((id, tab) in patch.customTabs) {
                require(id == tab.id && tab.isValid)
                put(id, customTabJson(tab))
            }
        })
        patch.customTabOrder?.let { put("customTabOrder", JsonArray(it.map(::JsonPrimitive))) }
    }

    private fun readLibrary(value: JsonElement, allowCustomTabs: Boolean): LibraryPatch {
        val fields = value.documentObject(
            "showSystemSounds", "showRecordings", "minLengthSeconds", "legacyEncodingId", "lists", "customTabs", "customTabOrder",
        )
        require(allowCustomTabs || "customTabs" !in fields && "customTabOrder" !in fields)
        return LibraryPatch(
            showSystemSounds = fields["showSystemSounds"]?.documentBoolean(),
            showRecordings = fields["showRecordings"]?.documentBoolean(),
            minLengthSeconds = fields["minLengthSeconds"]?.documentInt()?.also { require(it in HiddenFiles.LENGTHS) },
            legacyEncodingId = fields["legacyEncodingId"]?.documentString()?.also { id ->
                require(id == "automatic" || LegacyEncodings.ALL.any { it.name == id })
            },
            lists = fields["lists"]?.asObject().orEmpty().mapEntries(listNames::knownId) { name, entry ->
                val list = entry.documentObject("field", "reversed", "grid")
                ListPatch(
                    field = list["field"]?.documentString()?.also { require(it in name.fields) },
                    reversed = list["reversed"]?.documentBoolean(),
                    grid = list["grid"]?.documentBoolean()?.also { require(name.offersGrid) },
                )
            },
            customTabs = fields["customTabs"]?.asObject().orEmpty().mapEntries(::checkedCustomTabId) { id, entry ->
                entry.readUnit(CustomTabDefinitionDocument.serializer()).toDefinition(id) ?: errorShape()
            },
            customTabOrder = fields["customTabOrder"]?.let { value ->
                val order = (value as? JsonArray ?: errorShape()).map { checkedCustomTabId(it.documentString()) }
                require(order.size == order.toSet().size)
                order
            },
        )
    }

    fun customTabJson(tab: CustomTabDefinition): JsonElement =
        writeUnit(CustomTabDefinitionDocument.serializer(), CustomTabDefinitionDocument.from(tab))

    private fun checkedCustomTabId(id: String): String = id.also { require(isCustomTabId(it)) }

    fun appearanceJson(patch: AppearancePatch): JsonObject = orderedObject {
        add("builtInTheme", patch.builtInTheme?.stored)
        add("activeTheme", when (val active = patch.activeTheme) {
            null -> null
            ActiveTheme.BuiltIn -> BUILT_IN
            is ActiveTheme.Custom -> active.id
        })
        add("nowPlayingMoodBackground", patch.nowPlayingMoodBackground)
        addObject("builtInOverrides", orderedObject {
            for ((theme, layout) in patch.builtInOverrides) put(theme.stored, layoutJson(layout))
        })
        addObject("savedThemes", orderedObject {
            for ((id, theme) in patch.savedThemes) put(id, orderedObject {
                add("name", theme.name)
                add("base", theme.base?.stored)
                add("font", when (val font = theme.font) {
                    null, is ThemeFontSelection.Imported -> null
                    ThemeFontSelection.System -> "system"
                    is ThemeFontSelection.BuiltIn -> font.id
                })
                addObject("palette", paletteJson(theme.palette))
                addObject("layout", layoutJson(theme.layout))
            })
        })
    }

    private fun readAppearance(value: JsonElement, definedTabs: Set<String>?): AppearancePatch {
        val options = value.documentObject("builtInTheme", "activeTheme", "nowPlayingMoodBackground", "builtInOverrides", "savedThemes")
        return AppearancePatch(
            builtInTheme = options["builtInTheme"]?.let { themeModes.knownId(it.documentString()) },
            activeTheme = options["activeTheme"]?.documentString()?.let { id ->
                if (id == BUILT_IN) ActiveTheme.BuiltIn else ActiveTheme.Custom(checkedThemeId(id))
            },
            nowPlayingMoodBackground = options["nowPlayingMoodBackground"]?.documentBoolean(),
            builtInOverrides = options["builtInOverrides"]?.asObject().orEmpty()
                .mapEntries(themeModes::knownId) { _, layout -> readLayout(layout, definedTabs) },
            savedThemes = options["savedThemes"]?.asObject().orEmpty()
                .mapEntries(::checkedThemeId) { _, theme -> readSavedTheme(theme, definedTabs) },
        )
    }

    private fun readSavedTheme(value: JsonElement, definedTabs: Set<String>?): SavedThemePatch {
        val fields = value.documentObject("name", "base", "palette", "font", "layout")
        return SavedThemePatch(
            name = fields["name"]?.documentString()?.also { require(validConfigurationThemeName(it)) },
            base = fields["base"]?.let { themeModes.knownId(it.documentString()) },
            palette = fields["palette"]?.let(::readPalette) ?: PalettePatch(),
            font = fields["font"]?.documentString()?.let { id ->
                if (id == "system") ThemeFontSelection.System
                else ThemeFontSelection.BuiltIn(id).also { require(CustomThemesSettings.isValidFont(it)) }
            },
            layout = fields["layout"]?.let { readLayout(it, definedTabs) } ?: ThemeLayoutPatch(),
        )
    }

    private fun layoutJson(patch: ThemeLayoutPatch): JsonObject = orderedObject {
        addExplicit("presentation", patch.presentation) { JsonPrimitive(it.stored) }
        addExplicit("library", patch.library) { writeUnit(LibraryPanelDocument.serializer(), LibraryPanelDocument.from(it)) }
        addExplicit("player", patch.player) { writeUnit(NowPlayingPanelDocument.serializer(), NowPlayingPanelDocument.from(it)) }
        addExplicit("miniPlayer", patch.miniPlayer) { writeUnit(MiniPlayerPanelDocument.serializer(), MiniPlayerPanelDocument.from(it)) }
        addExplicit("navigation", patch.navigation) { writeUnit(BottomNavigationDocument.serializer(), BottomNavigationDocument.from(it)) }
        addObject("canvases", orderedObject {
            for ((surface, layout) in patch.canvases) addExplicit(surfaces.getValue(surface), layout) {
                writeUnit(UiCanvasLayout.serializer(), it)
            }
        })
        addExplicit("gestures", patch.gestures) { writeUnit(UiGestureProfile.serializer(), it) }
    }

    private fun readLayout(value: JsonElement, definedTabs: Set<String>?): ThemeLayoutPatch {
        val fields = value.documentObject("presentation", "library", "player", "miniPlayer", "navigation", "canvases", "gestures")
        return ThemeLayoutPatch(
            presentation = fields["presentation"].explicit { presentations.knownId(it.documentString()) },
            library = fields["library"].explicit {
                val panel = it.readUnit(LibraryPanelDocument.serializer())
                val mentioned = definedTabs?.plus(panel.referencedCustomTabIds()).orEmpty()
                panel.toLayout(mentioned) ?: errorShape()
            },
            player = fields["player"].explicit { it.readUnit(NowPlayingPanelDocument.serializer()).toPanel() ?: errorShape() },
            miniPlayer = fields["miniPlayer"].explicit { it.readUnit(MiniPlayerPanelDocument.serializer()).toPanel() ?: errorShape() },
            navigation = fields["navigation"].explicit { it.readUnit(BottomNavigationDocument.serializer()).toLayout() ?: errorShape() },
            canvases = fields["canvases"]?.asObject().orEmpty().mapEntries(surfaces::knownId) { _, element ->
                Explicit(if (element == JsonNull) null else
                    element.readUnit(UiCanvasLayout.serializer()).also { require(it.isValid) })
            },
            gestures = fields["gestures"].explicit { it.readUnit(UiGestureProfile.serializer()).also { gestures -> require(gestures.isValid) } },
        )
    }

    private fun paletteJson(patch: PalettePatch): JsonObject = orderedObject {
        addExplicit("accentRgb", patch.accentRgb, ::JsonPrimitive)
        addExplicit("backgroundRgb", patch.backgroundRgb, ::JsonPrimitive)
        addExplicit("surfaceRgb", patch.surfaceRgb, ::JsonPrimitive)
        addExplicit("textRgb", patch.textRgb, ::JsonPrimitive)
        add("automaticText", patch.automaticText)
    }

    private fun readPalette(value: JsonElement): PalettePatch {
        val fields = value.documentObject("accentRgb", "backgroundRgb", "surfaceRgb", "textRgb", "automaticText")
        return PalettePatch(
            accentRgb = fields["accentRgb"].explicit(::readRgb),
            backgroundRgb = fields["backgroundRgb"].explicit(::readRgb),
            surfaceRgb = fields["surfaceRgb"].explicit(::readRgb),
            textRgb = fields["textRgb"].explicit(::readRgb),
            automaticText = fields["automaticText"]?.documentBoolean(),
        )
    }

    private fun readRgb(value: JsonElement): Int = value.documentInt().also { require(it in 0..0xFFFFFF) }

    private fun checkedThemeId(id: String): String = id.also { require(CustomThemesSettings.isValidId(it)) }
}

private fun <T> Map<T, String>.knownId(id: String): T = entries.firstOrNull { it.value == id }?.key ?: errorShape()

internal fun validConfigurationThemeName(name: String): Boolean =
    name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl) && Charsets.UTF_8.newEncoder().canEncode(name)

private inline fun <K, V> Map<String, JsonElement>.mapEntries(
    key: (String) -> K,
    value: (K, JsonElement) -> V,
): Map<K, V> = entries.associate { (id, element) ->
    val checked = key(id)
    checked to value(checked, element)
}

private fun orderedObject(block: JsonObjectBuilder.() -> Unit): JsonObject =
    JsonObject(buildJsonObject(block).toSortedMap())

private fun JsonObjectBuilder.add(key: String, value: String?) { if (value != null) put(key, JsonPrimitive(value)) }
private fun JsonObjectBuilder.add(key: String, value: Int?) { if (value != null) put(key, JsonPrimitive(value)) }
private fun JsonObjectBuilder.add(key: String, value: Boolean?) { if (value != null) put(key, JsonPrimitive(value)) }
private fun JsonObjectBuilder.addObject(key: String, value: JsonObject) { if (value.isNotEmpty()) put(key, value) }

private fun <T : Any> JsonObjectBuilder.addExplicit(name: String, value: Explicit<T>?, encode: (T) -> JsonElement) {
    if (value != null) put(name, value.value?.let(encode) ?: JsonNull)
}

private fun <T : Any> JsonElement?.explicit(read: (JsonElement) -> T): Explicit<T>? =
    this?.let { Explicit(if (it == JsonNull) null else read(it)) }
