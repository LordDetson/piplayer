package io.github.lorddetson.piplayer.library.values

import java.nio.charset.Charset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Tags in a legacy encoding, decoded per file (TI §3.6). Each byte at or above 0x80 reaches the app marked. */
class LegacyTextTest {

    private fun marked(bytes: ByteArray): String = buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
        }
    }

    private fun cp1251(text: String) = marked(text.toByteArray(Charset.forName("windows-1251")))
    private fun latin1(text: String) = marked(text.toByteArray(Charsets.ISO_8859_1))
    private fun utf8(text: String) = marked(text.toByteArray(Charsets.UTF_8))

    private fun decode(vararg fields: String, cyrillicLanguage: Boolean = true) =
        LegacyText.decode(fields.withIndex().associate { (i, value) -> "KEY$i" to listOf(value) }, cyrillicLanguage)

    private fun LegacyText.Decoded.value(i: Int = 0) = properties.getValue("KEY$i").single()

    @ParameterizedTest
    @CsvSource("Кино", "Ёжик", "Би-2", "ДДТ", "БГ", "Білий птах", "Сплин")
    fun `Cyrillic names written in Windows-1251 are found`(text: String) {
        val decoded = decode(cp1251(text))

        assertThat(decoded.value()).isEqualTo(text)
        assertThat(decoded.encoding).isEqualTo(LegacyText.WINDOWS_1251)
    }

    @ParameterizedTest
    @CsvSource("Beyoncé", "Motörhead", "Sigur Rós", "ÉTÉ", "Chanson à boire", "À bout de souffle", "Ela é linda", "Lei è bella", "Ed × Sheeran", "Ed ÷ Sheeran", "Öö")
    fun `Western names stay Latin-1 even on a phone with a Cyrillic language`(text: String) {
        val decoded = decode(latin1(text))

        assertThat(decoded.value()).isEqualTo(text)
        assertThat(decoded.encoding).isEqualTo(LegacyText.LATIN_1)
    }

    @Test
    fun `the choice is made over all fields of the file`() {
        val decoded = decode(cp1251("МакSим"), cp1251("Знаешь ли ты"))

        assertThat(decoded.value(0)).isEqualTo("МакSим")
        assertThat(decoded.value(1)).isEqualTo("Знаешь ли ты")
        assertThat(decoded.encoding).isEqualTo(LegacyText.WINDOWS_1251)
    }

    @Test
    fun `more mixed words than Cyrillic ones stay Latin-1`() {
        assertThat(decode(latin1("Motörhead Beyoncé Þú")).encoding).isEqualTo(LegacyText.LATIN_1)
    }

    @Test
    fun `without a Cyrillic language nothing is taken for Windows-1251`() {
        for (text in listOf("Кино", "Þú", "ÅÄÖ", "Àà")) {
            val bytes = if (text == "Кино") cp1251(text) else latin1(text)
            assertThat(decode(bytes, cyrillicLanguage = false).encoding).describedAs(text).isEqualTo(LegacyText.LATIN_1)
        }
    }

    @Test
    fun `other legacy text is the known limit of the language condition`() {
        // These read as words in Windows-1251, which is why the language condition stays (TI §3.6).
        for (text in listOf("Þú", "ÅÄÖ", "Àà")) {
            assertThat(decode(latin1(text), cyrillicLanguage = true).encoding).describedAs(text).isEqualTo(LegacyText.WINDOWS_1251)
        }
    }

    @Test
    fun `a single Cyrillic letter is not enough`() {
        assertThat(decode(cp1251("Я")).encoding).isEqualTo(LegacyText.LATIN_1)
    }

    @Test
    fun `UTF-8 written into a Latin-1 field is read as UTF-8`() {
        val decoded = decode(utf8("Кино"), utf8("Beyoncé"))

        assertThat(decoded.value(0)).isEqualTo("Кино")
        assertThat(decoded.value(1)).isEqualTo("Beyoncé")
        assertThat(decoded.encoding).isEqualTo(LegacyText.UTF_8)
    }

    @Test
    fun `UTF-8 needs every field to be valid`() {
        assertThat(decode(utf8("Кино"), cp1251("Сплин")).encoding).isNotEqualTo(LegacyText.UTF_8)
    }

    @Test
    fun `text without marks decides nothing and stays as it is`() {
        val decoded = decode("Ascii title", "Юникод из кадра UTF-16")

        assertThat(decoded.value(0)).isEqualTo("Ascii title")
        assertThat(decoded.value(1)).isEqualTo("Юникод из кадра UTF-16")
        assertThat(decoded.encoding).isNull()
    }

    @Test
    fun `fields without marks stay as they are beside decoded ones`() {
        val decoded = decode(cp1251("Кино"), "Юникод из кадра UTF-16")

        assertThat(decoded.value(0)).isEqualTo("Кино")
        assertThat(decoded.value(1)).isEqualTo("Юникод из кадра UTF-16")
        assertThat(decoded.encoding).isEqualTo(LegacyText.WINDOWS_1251)
    }

    @Test
    fun `every value of a field is decoded`() {
        val decoded = LegacyText.decode(mapOf("GENRE" to listOf(cp1251("Рок"), cp1251("Поп"))), cyrillicLanguage = true)

        assertThat(decoded.properties.getValue("GENRE")).containsExactly("Рок", "Поп")
    }

    @Test
    fun `the upper half of Windows-1251 matches the platform's charset`() {
        val charset = Charset.forName("windows-1251")
        for (value in 0x80..0xFF) {
            val expected = String(byteArrayOf(value.toByte()), charset)
            assertThat(LegacyText.windows1251(value).toString()).describedAs("0x%02X".format(value)).isEqualTo(expected)
        }
    }

    @Test
    fun `Latin-1 repairs the range Windows-1252 uses for punctuation`() {
        val charset = Charset.forName("windows-1252")
        for (value in 0x80..0xFF) {
            val expected = String(byteArrayOf(value.toByte()), charset)
            assertThat(LegacyText.latin1(value).toString()).describedAs("0x%02X".format(value)).isEqualTo(expected)
        }
    }

    @Test
    fun `the Cyrillic languages are Russian, Belarusian and Ukrainian`() {
        assertThat(LegacyText.isCyrillicLanguage(listOf("en", "ru"))).isTrue()
        assertThat(LegacyText.isCyrillicLanguage(listOf("be"))).isTrue()
        assertThat(LegacyText.isCyrillicLanguage(listOf("uk"))).isTrue()
        assertThat(LegacyText.isCyrillicLanguage(listOf("en", "de"))).isFalse()
        assertThat(LegacyText.isCyrillicLanguage(emptyList())).isFalse()
    }
}
