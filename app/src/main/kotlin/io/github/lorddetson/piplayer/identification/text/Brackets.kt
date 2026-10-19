package io.github.lorddetson.piplayer.identification.text

/**
 * Bracketed parts at the end of a title, such as "(Live)" or "[Remix]"; ASCII and full-width brackets count. Brackets
 * earlier in the title, as in "(Don't Fear) The Reaper", belong to the name.
 */
internal object Brackets {

    private val trailingGroup = Regex("""\s*[(\[（［]([^()\[\]（）［］]*)[)\]）］]\s*$""")

    /** The name and the texts of its trailing bracketed groups, in order; a title that is all brackets is all name. */
    fun groups(title: String): Pair<String, List<String>> {
        var name = Spaces.plain(title).trim()
        val groups = ArrayDeque<String>()
        while (true) {
            val match = trailingGroup.find(name) ?: break
            val before = name.substring(0, match.range.first).trim()
            if (before.isEmpty()) break
            groups.addFirst(match.groupValues[1].trim())
            name = before
        }
        return name to groups.toList()
    }

    /** The name and the trailing bracketed texts joined by spaces. */
    fun split(title: String): Pair<String, String> = groups(title).let { (name, groups) -> name to groups.joinToString(" ") }
}
