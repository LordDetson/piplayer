package io.github.lorddetson.piplayer.identification.text

import java.text.Normalizer
import java.util.Locale

/**
 * Brings titles and artist names to one form before they are compared: lower case, no diacritics outside Cyrillic,
 * plain spellings of Latin letters that have no decomposition ("ø" as "o", "ß" as "ss"), "ё" as "е", halfwidth kana as
 * full kana, no apostrophes, punctuation as spaces, single spaces.
 *
 * Cyrillic, kana, Han and Hangul letters are kept whole: decomposing them would turn "й" into "и", "ў" into "у" and
 * "ド" into "ト", and Hangul syllables into separate jamo.
 */
object TextNormalizer {

    private val apostrophes = setOf('\'', '‘', '’', '`', '´', 'ʼ')
    private val wholeScripts = setOf(
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HANGUL,
    )

    /** Lower-case Latin letters that Unicode does not split into a base letter and a mark. */
    private val plainSpellings = mapOf(
        'ø' to "o",
        'ł' to "l",
        'đ' to "d",
        'ð' to "d",
        'æ' to "ae",
        'œ' to "oe",
        'ß' to "ss",
        'ı' to "i",
        'þ' to "th",
        'ħ' to "h",
        'ŧ' to "t",
        'ŋ' to "ng",
    )

    /**
     * Halfwidth katakana, their marks and punctuation. Only these get the compatibility form up front: for the whole
     * text it would also split characters that are handled on their own, such as the acute accent used as an apostrophe.
     */
    private val halfwidthKana = Regex("[\uFF61-\uFF9F]+")
    private val HALFWIDTH_KANA = '\uFF61'..'\uFF9F'

    /** All its letters are of the Cyrillic script, which keeps them whole. */
    private val CYRILLIC = '\u0400'..'\u04FF'

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        fun appendLetters(letters: String) {
            if (letters.isEmpty()) return
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(letters)
        }

        fun appendLetter(letter: Char) {
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(letter)
        }

        // A first sync brings here every value of the library, so what most text is made of takes the short way.
        val fullKana = if (text.any { it in HALFWIDTH_KANA }) {
            halfwidthKana.replace(text) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
        } else {
            text
        }
        val composed = if (fullKana.all { it.code < 0x80 }) fullKana else Normalizer.normalize(fullKana, Normalizer.Form.NFC)
        var i = 0
        while (i < composed.length) {
            val char = composed[i]
            if (char.code < 0x80) {
                i++
                when {
                    char == '\'' || char == '`' -> Unit
                    char in 'a'..'z' || char in '0'..'9' -> appendLetter(char)
                    char in 'A'..'Z' -> appendLetter(char + ('a' - 'A'))
                    else -> space = true
                }
                continue
            }
            if (char in CYRILLIC && char.isLetter()) {
                i++
                val lower = char.lowercaseChar()
                appendLetter(if (lower == 'ё') 'е' else lower)
                continue
            }
            val codePoint = composed.codePointAt(i)
            i += Character.charCount(codePoint)
            when {
                codePoint <= 0xFFFF && codePoint.toChar() in apostrophes -> Unit
                Character.UnicodeScript.of(codePoint) in wholeScripts -> {
                    if (Character.isLetterOrDigit(codePoint)) appendLetters(whole(codePoint)) else space = true
                }
                else -> {
                    val decomposed = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKD)
                    val kept = StringBuilder()
                    var j = 0
                    while (j < decomposed.length) {
                        val part = decomposed.codePointAt(j)
                        j += Character.charCount(part)
                        when {
                            Character.getType(part) == Character.NON_SPACING_MARK.toInt() -> Unit
                            Character.isLetterOrDigit(part) -> kept.appendCodePoint(part)
                            else -> {
                                appendLetters(plain(kept))
                                kept.clear()
                                space = true
                            }
                        }
                    }
                    appendLetters(plain(kept))
                }
            }
        }
        return out.toString()
    }

    private fun plain(letters: CharSequence): String = buildString(letters.length) {
        for (char in letters.toString().lowercase(Locale.ROOT)) {
            val spelling = plainSpellings[char]
            if (spelling != null) append(spelling) else append(char)
        }
    }

    private fun whole(codePoint: Int): String {
        val lower = String(Character.toChars(codePoint)).lowercase(Locale.ROOT)
        return if (lower == "ё") "е" else lower
    }
}
