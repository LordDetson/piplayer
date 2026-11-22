package io.github.lorddetson.piplayer.settings

import java.io.IOException
import java.io.InputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ThemePackageCodecTest {
    @Test
    fun `a version one file uses stable ids and parses Unicode names and optional overrides`() {
        val expected = ThemePackage(
            name = "Ночная тема",
            base = ThemeMode.GRAPHITE,
            palette = ThemePalette(accentRgb = 0xFFFFFF, backgroundRgb = 0, textRgb = 7, automaticText = false),
            layout = NowPlayingLayout.EXPANDED,
        )

        val encoded = ThemePackageCodec.encode(expected)
        assertThat(encoded.decodeToString()).contains("\"version\":1", "\"graphite\"", "\"expanded\"")
        assertThat(ThemePackageCodec.decode(encoded.inputStream())).isEqualTo(ThemePackageRead.Loaded(expected))
        assertThat(decode("""{"version":1,"name":"Ночная тема","base":"graphite","layout":"expanded",
            "palette":{"accentRgb":16777215,"backgroundRgb":0,"textRgb":7,"automaticText":false}}"""))
            .isEqualTo(ThemePackageRead.Loaded(expected))

        val punctuation = expected.copy(name = "Mix [42] {Demo} \"Night\"")
        assertThat(ThemePackageCodec.decode(ThemePackageCodec.encode(punctuation).inputStream()))
            .isEqualTo(ThemePackageRead.Loaded(punctuation))
    }

    @Test
    fun `a missing palette and layout inherit the local choices`() {
        assertThat(decode(document)).isEqualTo(ThemePackageRead.Loaded(ThemePackage("Example", ThemeMode.SYSTEM)))
    }

    @Test
    fun `known fields and stable identifiers are required`() {
        for (json in listOf(
            document.replace("\"system\"", "\"future_base\""),
            document.withField(""""layout":"custom_code""""),
            document.withField(""""script":"external.js""""),
            document.withField(""""palette":{"badRole":123}"""),
            document.withField(""""palette":{"accentRgb":-1}"""),
            document.withField(""""palette":{"textRgb":16777216}"""),
            document.replace("Example", " "),
            document.replace("Example", "\\u0000"),
            document.replace("Example", "x".repeat(81)),
        )) {
            assertThat(decode(json)).describedAs(json).isEqualTo(ThemePackageRead.Invalid)
        }
    }

    @Test
    fun `palette expects unquoted numbers and booleans and no nested structures`() {
        assertThat(decode(document.withField(""""palette":{"accentRgb":"123"}""")))
            .isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(document.withField(""""palette":{"automaticText":"false"}""")))
            .isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(document.withField(""""palette":{"accentRgb":{"value":123}}""")))
            .isEqualTo(ThemePackageRead.Invalid)
        val nestedArrays = "[".repeat(2048) + "0" + "]".repeat(2048)
        assertThat(decode(document.withField("\"palette\":$nestedArrays"))).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `future versions are distinguished from invalid schema markers`() {
        assertThat(decode(document.replace(":1,", ":7,"))).isEqualTo(ThemePackageRead.UnsupportedVersion)
        assertThat(decode(document.replace(":1,", ":6,"))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(document.replace(":1,", ":\"1\","))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(document.replace("\"version\":1,", ""))).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `a version five theme preserves the bottom navigation order and keeps a reachable destination`() {
        val source = """{"version":5,"name":"Mix","base":"dark","navigation":{"order":"search,library,fix","hidden":"fix"}}"""
        val loaded = decode(source)
        assertThat(loaded).isInstanceOf(ThemePackageRead.Loaded::class.java)
        val bytes = ThemePackageCodec.encode((loaded as ThemePackageRead.Loaded).theme)
        assertThat(bytes.decodeToString()).contains("\"version\":5", "search,library,fix", "\"hidden\":\"fix\"")
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(loaded)
        assertThat(decode(source.replace("\"hidden\":\"fix\"", "\"hidden\":\"search,library,fix\""))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(source.replace("search,library,fix", "search,library,library"))).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `a version four theme preserves the mini player order progress edge and hidden elements`() {
        val source = """{"version":4,"name":"Mix","base":"dark","miniPlayer":{"order":"play_pause,cover,track_info","hidden":"track_info,progress","progress":"bottom"}}"""
        val loaded = decode(source)
        assertThat(loaded).isInstanceOf(ThemePackageRead.Loaded::class.java)
        val bytes = ThemePackageCodec.encode((loaded as ThemePackageRead.Loaded).theme)
        assertThat(bytes.decodeToString()).contains("\"version\":4", "play_pause,cover,track_info", "\"progress\":\"bottom\"")
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(loaded)
        assertThat(decode(source.replace("play_pause,cover,track_info", "play_pause,cover,cover"))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(source.replace("play_pause,cover,track_info", "play_pause,progress,track_info"))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(source.replace("\"bottom\"", "\"side\""))).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `a version three theme preserves moved and hidden now playing elements`() {
        val source = """{"version":3,"name":"Mix","base":"dark","player":{"top":"identify,favorite,more","content":"track_info,cover,format,seek","controls":"shuffle,previous,queue,play_pause,next,repeat","extras":"timer,stop_after,equalizer","hidden":"cover,shuffle"}}"""
        val loaded = decode(source)
        assertThat(loaded).isInstanceOf(ThemePackageRead.Loaded::class.java)
        val bytes = ThemePackageCodec.encode((loaded as ThemePackageRead.Loaded).theme)
        assertThat(bytes.decodeToString()).contains("\"version\":3", "\"hidden\":\"cover,shuffle\"", "previous,queue,play_pause")
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(loaded)
        assertThat(decode(source.replace("track_info,cover,format,seek", "track_info,queue,cover,format,seek")))
            .isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(source.replace("previous,queue,play_pause", "previous,play_pause"))).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `a version two theme preserves the order and visibility of tabs and buttons`() {
        val source = """{"version":2,"name":"Mix","base":"dark","panel":{"tabOrder":"albums,songs,artists,playlists,genres,folders","hiddenTabs":"artists","buttonOrder":"settings,search,sort","hiddenButtons":"sort"}}"""
        val loaded = decode(source)
        assertThat(loaded).isInstanceOf(ThemePackageRead.Loaded::class.java)
        val bytes = ThemePackageCodec.encode((loaded as ThemePackageRead.Loaded).theme)
        assertThat(bytes.decodeToString()).contains("\"version\":2", "\"hiddenTabs\":\"artists\"", "\"hiddenButtons\":\"sort\"", "\"buttonOrder\":\"settings,search,sort\"")
        assertThat(ThemePackageCodec.decode(bytes.inputStream())).isEqualTo(loaded)
        assertThat(decode(source.replace("settings,search,sort", "custom,search,sort"))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(source.replace("\"hiddenTabs\":\"artists\"", "\"hiddenTabs\":\"albums,songs,artists,playlists,genres,folders\"")))
            .isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `a truncated file trailing content and malformed UTF8 are rejected`() {
        assertThat(decode(document.dropLast(1))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode("$document $document")).isEqualTo(ThemePackageRead.Invalid)
        val malformed = document.substringBefore("Example").encodeToByteArray() +
            byteArrayOf(0xC3.toByte(), 0x28) + document.substringAfter("Example").encodeToByteArray()
        assertThat(ThemePackageCodec.decode(malformed.inputStream())).isEqualTo(ThemePackageRead.Invalid)
    }

    @Test
    fun `size is bounded even when a provider has no advertised length`() {
        val padded = document.padEnd(ThemePackageCodec.LEGACY_MAX_BYTES, ' ')
        assertThat(decode(padded)).isInstanceOf(ThemePackageRead.Loaded::class.java)
        assertThat(decode("$padded ")).isEqualTo(ThemePackageRead.TooLarge)
        val canvas = """{"version":6,"name":"Test","base":"system","canvases":{"panels":{}}}"""
            .padEnd(ThemePackageCodec.MAX_BYTES, ' ')
        assertThat(decode(canvas)).isInstanceOf(ThemePackageRead.Loaded::class.java)
        assertThat(decode("$canvas ")).isEqualTo(ThemePackageRead.TooLarge)
    }

    @Test
    fun `a broken content stream is distinguished from an invalid package`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Source removed")
        }
        assertThat(ThemePackageCodec.decode(broken)).isEqualTo(ThemePackageRead.Unreadable)
    }

    @Test
    fun `export validates the data before writing it`() {
        assertThatIllegalArgumentException().isThrownBy {
            ThemePackageCodec.encode(ThemePackage("", ThemeMode.SYSTEM))
        }
        assertThatIllegalArgumentException().isThrownBy {
            ThemePackageCodec.encode(ThemePackage("Invalid color", ThemeMode.SYSTEM, ThemePalette(surfaceRgb = -2)))
        }
    }

    private fun decode(json: String) = ThemePackageCodec.decode(json.byteInputStream())

    private fun String.withField(field: String) = dropLast(1) + ",$field}"

    private val document = """{"version":1,"name":"Example","base":"system"}"""
}
