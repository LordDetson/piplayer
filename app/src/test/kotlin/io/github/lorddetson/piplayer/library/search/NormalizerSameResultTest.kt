package io.github.lorddetson.piplayer.library.search

import io.github.lorddetson.piplayer.identification.text.TextNormalizer
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A first sync normalizes every value of 20 000 files, so the normalizer and the search text have fast paths for what most
 * text is made of. This holds them to the plain form they replace, written out here, on text of every kind they meet.
 */
class NormalizerSameResultTest {

    private object Plain {
        val apostrophes = setOf('\'', '‘', '’', '`', '´', 'ʼ')
        val wholeScripts = setOf(
            Character.UnicodeScript.CYRILLIC,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HANGUL,
        )
        val spellings = mapOf(
            'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'ı' to "i", 'þ' to "th",
            'ħ' to "h", 'ŧ' to "t", 'ŋ' to "ng",
        )
        val halfwidthKana = Regex("[｡-ﾟ]+")
        val wordBreak = Regex("""[\s\p{Z}_]+""")
        val shortWords = Regex("""(?<!\S)\S{1,2}(?: \S{1,2})+(?!\S)""")

        fun normalize(text: String): String {
            val out = StringBuilder(text.length)
            var space = false
            fun append(letters: String) {
                if (letters.isEmpty()) return
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.append(letters)
            }

            fun plain(letters: CharSequence) = buildString {
                for (char in letters.toString().lowercase(Locale.ROOT)) {
                    val spelling = spellings[char]
                    if (spelling != null) append(spelling) else append(char)
                }
            }

            fun whole(codePoint: Int): String {
                val lower = String(Character.toChars(codePoint)).lowercase(Locale.ROOT)
                return if (lower == "ё") "е" else lower
            }

            val fullKana = halfwidthKana.replace(text) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
            val composed = Normalizer.normalize(fullKana, Normalizer.Form.NFC)
            var i = 0
            while (i < composed.length) {
                val codePoint = composed.codePointAt(i)
                i += Character.charCount(codePoint)
                when {
                    codePoint <= 0xFFFF && codePoint.toChar() in apostrophes -> Unit
                    Character.UnicodeScript.of(codePoint) in wholeScripts -> {
                        if (Character.isLetterOrDigit(codePoint)) append(whole(codePoint)) else space = true
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
                                    append(plain(kept))
                                    kept.clear()
                                    space = true
                                }
                            }
                        }
                        append(plain(kept))
                    }
                }
            }
            return out.toString()
        }

        fun indexed(field: String): String {
            val normalized = normalize(field)
            val splitWords = field.split(wordBreak)
                .filter { word -> word.any { !it.isLetterOrDigit() } }
                .map(::normalize)
                .filter { ' ' in it }
            val shortRuns = shortWords.findAll(normalized).map { it.value }
            val glued = (splitWords + shortRuns).map { it.replace(" ", "") }.distinct()
            return (listOf(normalized) + glued).joinToString(" ")
        }
    }

    private val fixed = listOf(
        "", " ", "AC/DC", "AC DC", "a b c long d e", "Ёжик в тумане", "ЁЖИК", "Би-2", "БИ 2", "Beyoncé", "Motörhead", "Sigur Rós",
        "Ø Ł Đ Æ Œ ß ı İ", "ÀÉÎÕÜ", "don't", "don’t", "rock'n'roll", "`quoted´", "ｶﾞｷﾞ ｱｲｳ", "東京事変", "方大同 ＡＢＣ", "방탄소년단",
        "ÅÄÖ", "٣ أ", "ᾧ", "ǅ", "ﬁ", "№5", "x²", "Ⅷ", "U+0301: é", "й", "ё", "😀 emoji 𠀀", "\uD83D",
        "҃҅҆", "ЀӀӏԯ", "a_b-c.d", "01 - Artist_Name - Song (Live) [2020].mp3", " nbsp em　id",
        "a\u0000b", "ς Σ σ", "Ƀ Ω K Å",
    )

    private fun corpus(): List<String> {
        val pieces = listOf(
            "abcXYZ0189", " ", "  ", "_-./,()[]'`", "ёЁйЙщЩъЪіІїЇєЄўЎ", "абвгдЖЯ", "éèêëÉÈàçñÑöÖüÜåÅ", "øØłŁđĐæÆœŒßıİþħŧŋ",
            "ｶﾞｷﾟｱ", "カタカナかな", "東京", "한글", "́̈", "😀𠀀", "  　", "ﬁ²Ⅷ№", "٣أب", "҃҅Ӏ",
        )
        val random = Random(20261105)
        return fixed + List(20_000) {
            buildString {
                repeat(random.nextInt(1, 9)) {
                    val piece = pieces[random.nextInt(pieces.size)]
                    val from = random.nextInt(piece.length)
                    append(piece, from, minOf(piece.length, from + random.nextInt(1, 6)))
                    if (random.nextInt(3) == 0) append(' ')
                }
            }
        }
    }

    @Test
    fun `the normalizer gives what the plain form gives`() {
        for (text in corpus()) {
            assertThat(TextNormalizer.normalize(text)).describedAs(text).isEqualTo(Plain.normalize(text))
        }
    }

    @Test
    fun `the search text gives what the plain form gives`() {
        for (text in corpus()) {
            assertThat(SearchText.of(text)).describedAs(text).isEqualTo(Plain.indexed(text).takeIf { it.isNotEmpty() } ?: "")
        }
    }
}
