package io.github.lorddetson.piplayer.identification.text

/**
 * Turns every kind of space (no-break, thin, ideographic, tabs) into a plain one. Store titles often put a no-break
 * space between a song's name and its version ("Girlfriend<NBSP>Radio Edit"), and `\s` in a pattern does not match it.
 */
internal object Spaces {

    private val spaces = Regex("""[\p{Z}\s]+""")

    fun plain(text: String): String = spaces.replace(text, " ")
}
