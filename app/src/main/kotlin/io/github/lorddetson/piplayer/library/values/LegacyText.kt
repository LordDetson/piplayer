package io.github.lorddetson.piplayer.library.values

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Tags in a legacy encoding, decoded per file over all its legacy fields (TI §3.6). The reader marks each byte at or
 * above 0x80 of ID3v1, encoding-0 ID3v2 frames and RIFF INFO, so the choice can be made here, where the languages are
 * known, and made again later without reading the file.
 */
object LegacyText {

    /** A byte at or above 0x80 of a legacy field arrives from the reader as this plus the byte. */
    const val MARK_BASE = 0xE000

    const val LATIN_1 = "iso-8859-1"
    const val WINDOWS_1251 = "windows-1251"
    const val UTF_8 = "utf-8"

    private const val FIRST_MARK = MARK_BASE + 0x80
    private const val LAST_MARK = MARK_BASE + 0xFF

    // Own tables: nothing promises that Android has these charsets (TI §3.6).
    private const val WINDOWS_1251_80_TO_BF =
        "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏ" +
            "ђ‘’“”•–—�™љ›њќћџ" +
            " ЎўЈ¤Ґ¦§Ё©Є«¬­®Ї" +
            "°±Ііґµ¶·ё№є»јЅѕї"
    private const val WINDOWS_1252_80_TO_9F =
        "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž�" +
            "�‘’“”•–—˜™š›œ�žŸ"

    private const val CYRILLIC_VOWELS = "аеёиоуыэюяіїєАЕЁИОУЫЭЮЯІЇЄ"
    private val cyrillicLanguages = setOf("ru", "be", "uk")

    /** [encoding] is what the legacy fields were read as; null when none holds a byte above ASCII. */
    class Decoded(val properties: Map<String, List<String>>, val encoding: String?)

    /** [cyrillicLanguage] allows Windows-1251; without that condition other legacy text would read as words in it. */
    fun decode(properties: Map<String, List<String>>, cyrillicLanguage: Boolean): Decoded {
        val legacy = properties.values.flatten().filter(::isMarked)
        if (legacy.isEmpty()) return Decoded(properties, null)
        val utf8 = legacy.associateWith(::utf8OrNull)
        val encoding = when {
            utf8.values.all { it != null } -> UTF_8
            cyrillicLanguage && readsAsCyrillic(legacy) -> WINDOWS_1251
            else -> LATIN_1
        }
        val decoded = properties.mapValues { (_, values) ->
            values.map { value ->
                when {
                    !isMarked(value) -> value
                    encoding == UTF_8 -> utf8.getValue(value)!!
                    encoding == WINDOWS_1251 -> value.unmarked(::windows1251)
                    else -> value.unmarked(::latin1)
                }
            }
        }
        return Decoded(decoded, encoding)
    }

    /** Whether one of the app's or the device's languages makes Windows-1251 a candidate. */
    fun isCyrillicLanguage(languages: List<String>) = languages.any { it in cyrillicLanguages }

    /** [byte] from 0x80 to 0xFF in Windows-1251. */
    fun windows1251(byte: Int): Char =
        if (byte < 0xC0) WINDOWS_1251_80_TO_BF[byte - 0x80] else 'А' + (byte - 0xC0)

    /** [byte] from 0x80 to 0xFF in Latin-1, with Windows-1252's punctuation in the range Latin-1 leaves to controls. */
    fun latin1(byte: Int): Char = if (byte < 0xA0) WINDOWS_1252_80_TO_9F[byte - 0x80] else byte.toChar()

    private fun isMarked(value: String) = value.any { it.code in FIRST_MARK..LAST_MARK }

    private inline fun String.unmarked(decoded: (Int) -> Char): String = buildString(length) {
        for (char in this@unmarked) {
            append(if (char.code in FIRST_MARK..LAST_MARK) decoded(char.code - MARK_BASE) else char)
        }
    }

    /** The value as UTF-8 written into a Latin-1 field; null when it is not valid UTF-8. */
    private fun utf8OrNull(value: String): String? {
        val bytes = ByteArray(value.length) { i ->
            val code = value[i].code
            when {
                code < 0x80 -> code.toByte()
                code in FIRST_MARK..LAST_MARK -> (code - MARK_BASE).toByte()
                else -> return null
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * More words that are real Cyrillic than words that mix scripts. A real word has at least two letters and a vowel or
     * is all upper case, which leaves out what Western accented letters turn into, such as "й" for "é".
     */
    private fun readsAsCyrillic(values: List<String>): Boolean {
        var cyrillic = 0
        var mixed = 0
        for (value in values) {
            for (word in words(value.unmarked(::windows1251))) {
                val letters = word.count(::isCyrillicLetter)
                when {
                    letters == 0 -> Unit
                    letters < word.length -> mixed++
                    word.length >= 2 && (word.any { it in CYRILLIC_VOWELS } || word.all { it.isUpperCase() }) -> cyrillic++
                }
            }
        }
        return cyrillic > 0 && cyrillic > mixed
    }

    private fun words(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (char in text) {
            if (char.isLetter()) {
                word.append(char)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    private fun isCyrillicLetter(char: Char) = char in 'Ѐ'..'ӿ' && char.isLetter()
}
