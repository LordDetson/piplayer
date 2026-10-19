package io.github.lorddetson.piplayer.identification.text

import java.text.Normalizer
import java.util.Locale

/**
 * Brings titles and artist names to one form before they are compared: lower case, no diacritics outside Cyrillic,
 * "ё" as "е", no apostrophes, punctuation as spaces, single spaces.
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

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        fun appendLetters(letters: String) {
            if (letters.isEmpty()) return
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(letters)
        }

        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        var i = 0
        while (i < composed.length) {
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
                                appendLetters(kept.toString().lowercase(Locale.ROOT))
                                kept.clear()
                                space = true
                            }
                        }
                    }
                    appendLetters(kept.toString().lowercase(Locale.ROOT))
                }
            }
        }
        return out.toString()
    }

    private fun whole(codePoint: Int): String {
        val lower = String(Character.toChars(codePoint)).lowercase(Locale.ROOT)
        return if (lower == "ё") "е" else lower
    }
}
