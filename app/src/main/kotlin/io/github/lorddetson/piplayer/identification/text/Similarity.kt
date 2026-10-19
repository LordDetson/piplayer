package io.github.lorddetson.piplayer.identification.text

import kotlin.math.max

/** String similarity from 0 to 1 used by scoring (TI §4.1). */
object Similarity {

    private const val SUFFIX_WEIGHT = 0.2

    /** Levenshtein ratio of two strings as given: 1 minus the edit distance divided by the longer length. */
    fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var previous = IntArray(y.size + 1) { it }
        var current = IntArray(y.size + 1)
        for (i in x.indices) {
            current[0] = i + 1
            for (j in y.indices) {
                val substitution = previous[j] + if (x[i] == y[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[y.size].toDouble() / max(x.size, y.size)
    }

    /** [ratio] of the normalized strings. */
    fun text(a: String, b: String): Double = ratio(TextNormalizer.normalize(a), TextNormalizer.normalize(b))

    /**
     * Title similarity: the names are compared as [text]; a trailing bracketed version such as "(Live)" or "[Remix]"
     * on only one side costs 0.2, and different versions on both sides cost more (0.2 plus up to 0.2). A featured
     * artist or a remaster note in brackets is not a version and is left out.
     *
     * When the two sides split differently ("Song (Part) Radio Edit" has no trailing group, "Song (Part)" has one), the
     * whole titles are compared too, at most at the price of a version on one side.
     */
    fun title(a: String, b: String): Double {
        val left = TitleParts.of(a)
        val right = TitleParts.of(b)
        if (left.base.isEmpty() || right.base.isEmpty()) return text(a, b)
        val base = ratio(left.base, right.base)
        val suffixes = ratio(left.suffix, right.suffix)
        val penalty = when {
            left.suffix.isEmpty() && right.suffix.isEmpty() -> 0.0
            left.suffix.isEmpty() || right.suffix.isEmpty() -> SUFFIX_WEIGHT
            suffixes >= SAME_SUFFIX -> 0.0
            else -> SUFFIX_WEIGHT + SUFFIX_WEIGHT * (1.0 - suffixes)
        }
        val split = (base - penalty).coerceIn(0.0, 1.0)
        return maxOf(split, minOf(text(a, b), 1.0 - SUFFIX_WEIGHT))
    }

    private const val SAME_SUFFIX = 0.9

    /** Bracketed notes that do not make another version of the song. */
    private val neutral = Regex(
        """(?:feat\b\.?|ft\b\.?|featuring\b|with\b)\s*.*|(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?""",
        RegexOption.IGNORE_CASE,
    )

    /** A title split into its normalized name and the normalized text of its trailing bracketed versions. */
    private data class TitleParts(val base: String, val suffix: String) {
        companion object {
            fun of(title: String): TitleParts {
                val (name, groups) = Brackets.groups(title)
                val versions = groups.filterNot { neutral.matches(it) }.joinToString(" ")
                return TitleParts(TextNormalizer.normalize(name), TextNormalizer.normalize(versions))
            }
        }
    }
}
