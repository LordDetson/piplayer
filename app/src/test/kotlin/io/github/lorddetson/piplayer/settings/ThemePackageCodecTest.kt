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
        assertThat(decode(document.replace(":1,", ":2,"))).isEqualTo(ThemePackageRead.UnsupportedVersion)
        assertThat(decode(document.replace(":1,", ":\"1\","))).isEqualTo(ThemePackageRead.Invalid)
        assertThat(decode(document.replace("\"version\":1,", ""))).isEqualTo(ThemePackageRead.Invalid)
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
        val padded = document.padEnd(ThemePackageCodec.MAX_BYTES, ' ')
        assertThat(decode(padded)).isInstanceOf(ThemePackageRead.Loaded::class.java)
        assertThat(decode("$padded ")).isEqualTo(ThemePackageRead.TooLarge)
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
