package io.github.lorddetson.piplayer.identification.corpus

/** One line of an exported liked-tracks list: "12. Title \u2014 Artist, Artist [03:45]". */
data class LikedTrack(val number: Int, val title: String, val artist: String, val durationMs: Long?)

/** Reads the owner's exported liked-tracks list, which stays outside the repository. */
object LikedTracksList {

    private val numbered = Regex("""^\s*(\d+)\.\s+(.+?)\s*$""")
    private val duration = Regex("""\s*\[(\d{1,3}):(\d{2})]$""")
    private const val SEPARATOR = " \u2014 "

    /** Parses one line, or returns null for headings, blank lines and other text. */
    fun parseLine(line: String): LikedTrack? {
        val match = numbered.matchEntire(line) ?: return null
        var rest = match.groupValues[2]
        val time = duration.find(rest)
        val durationMs = time?.let {
            (it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong()) * 1000
        }
        if (time != null) rest = rest.substring(0, time.range.first)
        val split = rest.lastIndexOf(SEPARATOR)
        if (split <= 0) return null
        val title = rest.substring(0, split).trim()
        val artist = rest.substring(split + SEPARATOR.length).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return LikedTrack(match.groupValues[1].toInt(), title, artist, durationMs)
    }

    fun parse(lines: Sequence<String>): List<LikedTrack> = lines.mapNotNull(::parseLine).toList()
}
