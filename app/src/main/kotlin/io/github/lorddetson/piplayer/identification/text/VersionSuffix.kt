package io.github.lorddetson.piplayer.identification.text

/**
 * Puts a version description written without brackets at the end of a title ("Night Work Original Mix",
 * "Song - slowed down") into brackets ("Night Work (Original Mix)"), the way MusicBrainz writes it, so that it is
 * compared as a suffix (TI §4.1) and does not count as a word of the song's name.
 */
object VersionSuffix {

    /** Words that describe a mix or an edit; any other word before "Remix" is part of the name. */
    private const val MODIFIER =
        """original|radio|extended|club|dub|vip|short|long|lofi|lo-fi|acoustic|instrumental|single|album|clean|""" +
            """explicit|edit|piano|orchestral|deluxe|alternate|alternative|demo"""

    private const val VERSION =
        """(?:(?:$MODIFIER)\s+)?(?:mix|edit|remix)|\p{L}+\s+version|""" +
            """slowed(?:\s+(?:down|\+\s*reverb|&\s*reverb|and\s+reverb))?|sped\s+up|speed\s+up|nightcore|instrumental|""" +
            """remaster(?:ed)?(?:\s+\d{4})?|\d{4}\s+remaster(?:ed)?|re-?recorded|bonus\s+track|""" +
            """from\s+.+?\s+soundtrack|from\s+["“«].*"""

    /** "Title Original Mix": the shortest name before a version description that ends the title. */
    private val trailing = Regex("""^(.*?\S)\s+($VERSION)$""", RegexOption.IGNORE_CASE)

    /** "Title - Live", "Title - Remixer Remix": after a dash, a few more words count as a version. */
    private val afterDash = Regex(
        """^(.*\S)\s+[-\u2013\u2014]\s+($VERSION|live|acoustic|cover|""" +
            """(?:[\p{L}\p{N}.'&]+\s+){1,4}(?:remix|mix|edit|version|bootleg|flip))$""",
        RegexOption.IGNORE_CASE,
    )

    /** A title that is nothing but a version description ("Original Mix") is the song's name. */
    private val versionOnly = Regex("""^(?:$VERSION)$""", RegexOption.IGNORE_CASE)

    fun bracket(title: String): String {
        val text = Spaces.plain(title).trim()
        if (versionOnly.matches(text)) return title
        val match = afterDash.matchEntire(text) ?: trailing.matchEntire(text) ?: return title
        return "${match.groupValues[1]} (${match.groupValues[2]})"
    }
}
