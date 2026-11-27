package io.github.lorddetson.piplayer.settings.configuration

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiControlStyle
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureMotionPreset
import io.github.lorddetson.piplayer.settings.UiGesturePoint
import io.github.lorddetson.piplayer.settings.UiGesturePreset
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiIconAsset
import io.github.lorddetson.piplayer.settings.UiIconFormat
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import java.io.IOException
import java.io.InputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ConfigurationCodecTest {
    @Test
    fun `portable document preserves explicit resets and stable identifiers`() {
        val patch = ConfigurationPatch(
            language = AppLanguage.RUSSIAN,
            library = LibraryPatch(legacyEncodingId = "automatic", lists = mapOf(ListName.SONGS to ListPatch(grid = true))),
            appearance = AppearancePatch(
                builtInTheme = ThemeMode.DARK,
                activeTheme = ActiveTheme.BuiltIn,
                builtInOverrides = mapOf(ThemeMode.DARK to ThemeLayoutPatch(
                    presentation = Explicit(null),
                    canvases = mapOf(UiSurface.LIBRARY to Explicit(UiCanvasLayout(
                        listOf(UiCanvasItem("tab:songs", UiCanvasBounds(0, 0, 500, 100))),
                        hiddenStock = setOf("tab:artists", "tab:albums"),
                    )), UiSurface.MINI_PLAYER to Explicit(null)),
                )),
            ),
        )
        val encoded = ConfigurationCodec.encode(patch)
        val text = encoded.decodeToString()
        assertThat(text).contains("\n  \"appearance\": {", "\"language\": \"ru\"", "\"activeTheme\": \"built_in\"",
            "\"presentation\": null", "\"mini_player\": null", "\"version\": 1").endsWith("\n")
        assertThat(ConfigurationCodec.decode(encoded.inputStream())).isEqualTo(ConfigurationRead.Loaded(patch))
        val layout = patch.appearance.builtInOverrides.getValue(ThemeMode.DARK)
        val canvas = layout.canvases.getValue(UiSurface.LIBRARY).value!!
        val reordered = layout.copy(canvases = layout.canvases + (UiSurface.LIBRARY to Explicit(
            canvas.copy(hiddenStock = setOf("tab:albums", "tab:artists")),
        )))
        assertThat(ConfigurationCodec.encode(patch.copy(appearance = patch.appearance.copy(
            builtInOverrides = mapOf(ThemeMode.DARK to reordered),
        )))).isEqualTo(encoded)
    }

    @Test
    fun `gesture paths animation keyframes and false settings survive a fresh import`() {
        val cover = UiGestureTarget.component("now:cover")
        val preset = UiGesturePreset("triangle", "Triangle", (0..31).map {
            val x = it / 31f
            UiGesturePoint(x, if (x < 0.5f) 2f * x else 2f * (1f - x))
        })
        val slot = UiGestureSlot(cover, UiGesture.custom(preset.id))
        val original = UiGestureSlot(cover, UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT))
        val replacement = UiGestureBinding(slot, UiGestureAction(UiGestureActionId.NEXT_TRACK),
            listOf(UiGestureMotionPreset.CARDS.timeline(cover)))
        val gestures = UiGestureProfile(listOf(preset), mapOf(
            UiSurface.NOW_PLAYING to UiSurfaceGestures(listOf(replacement), setOf(original)),
        ))
        val patch = ConfigurationPatch(
            sleepTimer = SleepTimerPatch(hours = 0, minutes = 0, finishTrack = false),
            appearance = AppearancePatch(
                nowPlayingMoodBackground = false,
                builtInOverrides = mapOf(ThemeMode.SYSTEM to ThemeLayoutPatch(gestures = Explicit(gestures))),
            ),
        )
        val encoded = ConfigurationCodec.encode(patch)
        assertThat(ConfigurationCodec.decode(encoded.inputStream())).isEqualTo(ConfigurationRead.Loaded(patch))
        val target = mutablePreferencesOf()
        assertThat(ConfigurationThemes.apply(patch.appearance, target)).isTrue()
        assertThat(ConfigurationThemes.snapshot(target).builtInOverrides).isEqualTo(patch.appearance.builtInOverrides)
    }

    @Test
    fun `gesture decimals have a platform independent spelling and preserve float bits`() {
        val midpoint = 513f / 1024f // Exactly 0.5009765625, halfway between two nine-digit decimals.
        val points = List(32) { index ->
            when (index) {
                0 -> UiGesturePoint(midpoint, 0.1f)
                1 -> UiGesturePoint(Float.MIN_VALUE, -0.0f)
                2 -> UiGesturePoint(Float.fromBits(0x00800000), 0.75f)
                else -> UiGesturePoint(index / 31f, 0.75f)
            }
        }
        val profile = UiGestureProfile(presets = listOf(UiGesturePreset("recorded", "Recorded", points)))
        val patch = ConfigurationPatch(appearance = AppearancePatch(builtInOverrides = mapOf(
            ThemeMode.SYSTEM to ThemeLayoutPatch(gestures = Explicit(profile)),
        )))

        val encoded = ConfigurationCodec.encode(patch)
        assertThat(encoded.decodeToString()).contains(
            "\"x\": 0.500976562,",
            "\"y\": 0.100000001",
            "\"x\": 1.40129846E-45,",
            "\"y\": -0.0",
        )
        val decoded = ConfigurationCodec.decode(encoded.inputStream()) as ConfigurationRead.Loaded
        val restored = decoded.patch.appearance.builtInOverrides.getValue(ThemeMode.SYSTEM)
            .gestures!!.value!!.presets.single().points
        assertThat(restored.map { it.x.toRawBits() to it.y.toRawBits() })
            .isEqualTo(points.map { it.x.toRawBits() to it.y.toRawBits() })
        assertThat(ConfigurationCodec.encode(decoded.patch)).isEqualTo(encoded)
        // Android 16 emits one fewer digit than older runtimes for the smallest normal Float.
        val modern = encoded.decodeToString().replace("1.17549435E-38", "1.1754944E-38")
        assertThat(modern).isNotEqualTo(encoded.decodeToString())
        assertThat(ConfigurationCodec.encode((read(modern) as ConfigurationRead.Loaded).patch)).isEqualTo(encoded)
    }

    @Test
    fun `many valid theme assets round trip below the cap and report oversize above it`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"><rect width=\"1\" height=\"1\"/>" +
            " ".repeat(8_900) + "</svg>"
        val style = UiControlStyle(icon = UiIconAsset(UiIconFormat.SVG, svg))
        val items = listOf("cover", "more", "seek", "identify", "favorite", "queue", "shuffle", "repeat", "previous",
            "next", "play_pause", "timer", "stop_after", "equalizer", "artist", "album", "title", "audio_badge").map { id ->
            UiCanvasItem("now:$id", UiCanvasBounds(0, 0, 100, 100), style = style)
        }
        val canvases = mapOf(UiSurface.NOW_PLAYING to UiCanvasLayout(items))
        assertThat(UiThemeCanvases(canvases).isValid).isTrue()
        val layout = ThemeLayoutPatch(canvases = canvases.mapValues { Explicit(it.value) })
        fun patch(themes: Int) = ConfigurationPatch(appearance = AppearancePatch(savedThemes = (1..themes).associate { index ->
            "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}" to SavedThemePatch("Theme $index", ThemeMode.SYSTEM, layout = layout)
        }))

        val small = patch(3)
        val bytes = ConfigurationCodec.encode(small)
        assertThat(ConfigurationCodec.decode(bytes.inputStream())).isEqualTo(ConfigurationRead.Loaded(small))
        assertThatThrownBy { ConfigurationCodec.encode(patch(30)) }.isExactlyInstanceOf(ConfigurationTooLargeException::class.java)
    }

    @Test
    fun `document cannot silently coerce keys numbers or booleans`() {
        for (fields in listOf(
            "\"language\":en",
            "\"language\":\"en-US\"",
            "\"library\":{\"showRecordings\":\"false\"}",
            "\"library\":{\"minLengthSeconds\":1e1}",
            "\"library\":{\"minLengthSeconds\":17}",
            "\"library\":{\"showRecordings\":false,\"show\\u0052ecordings\":true}",
            "\"library\":{\"lists\":{\"album_page\":{\"grid\":false}}}",
            "\"library\":{\"lists\":{\"songs\":{\"field\":\"year\"}}}",
            "\"sleepTimer\":{\"hours\":24}",
            "\"appearance\":{\"activeTheme\":null}",
            "\"appearance\":{\"surprise\":true}",
            "\"appearance\":{\"builtInOverrides\":{\"dark\":{\"canvases\":{\"library\":{\"items\":[],\"snapping\":\"true\"}}}}}",
            "\"appearance\":{\"builtInOverrides\":{\"dark\":{\"canvases\":{\"library\":{\"items\":[],\"hiddenStock\":[\"tab:songs\",\"tab:songs\"]}}}}}",
        )) {
            assertThat(read("{\"version\":1,$fields}")).describedAs(fields).isEqualTo(ConfigurationRead.Invalid)
        }
    }

    @Test
    fun `input is bounded and read outcomes are distinct`() {
        assertThat(read("{\"version\":2,\"future\":true}")).isEqualTo(ConfigurationRead.UnsupportedVersion)
        assertThat(read("{\"version\":\"2\"}")).isEqualTo(ConfigurationRead.Invalid)
        assertThat(read("{}")).isEqualTo(ConfigurationRead.Invalid)
        assertThat(read("{\"version\":1}{}")).isEqualTo(ConfigurationRead.Invalid)
        assertThat(ConfigurationCodec.decode(byteArrayOf(-64, -81).inputStream())).isEqualTo(ConfigurationRead.Invalid)
        assertThat(ConfigurationCodec.decode(ByteArray(4 * 1024 * 1024 + 1).inputStream()))
            .isEqualTo(ConfigurationRead.TooLarge)
        assertThat(ConfigurationCodec.decode(object : InputStream() {
            override fun read(): Int = throw IOException("missing")
        })).isEqualTo(ConfigurationRead.Unreadable)
    }

    private fun read(json: String) = ConfigurationCodec.decode(json.byteInputStream())
}
