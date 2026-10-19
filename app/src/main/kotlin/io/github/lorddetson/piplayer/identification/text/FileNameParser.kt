package io.github.lorddetson.piplayer.identification.text

/** What a file name says about its track; any part may be missing. */
data class ParsedFileName(val artist: String?, val title: String, val trackNumber: Int?)

/**
 * Guesses artist, title and track number from a file name (TI §3.3) such as "01 - Artist - Title (Remix).mp3" or
 * "Artist_-_Title.flac". Download-site noise ("[320kbps]", "(Official Video)", web addresses) and copy suffixes are
 * dropped; meaningful suffixes such as "(Live)" stay in the title. So are the traces of video downloaders: a video ID
 * ("Title [dQw4w9WgXcQ]", "Title-dQw4w9WgXcQ"), an "audio only" marker, a converter site in front ("y2mate.com - ")
 * and a bitrate at the end ("_128kbps").
 */
object FileNameParser {

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "wma", "ape", "wv", "mka", "webm", "aif",
        "aiff", "3gp", "amr", "mpc", "dsf", "dff",
    )
    private val separator = Regex("""\s+(?:-{1,2}|\u2013|\u2014)\s+""")
    private val bracketed = Regex("""\s*[(\[{]([^()\[\]{}]*)[)\]}]""")
    /** Bracketed noise; a bare number in brackets is a copy counter such as "(1)". */
    private val bracketNoise = Regex(
        """\d+|\d{2,3}\s*kbps|official(?:\s+(?:music|lyric))?\s+(?:video|audio|visualizer)|lyrics?(?:\s+video)?|""" +
            """audio(?:\s+only)?|hd|hq|visualizer|copy|копия""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** A trailing " - Copy" or " - audio only" part; a trailing number is a title such as "1999", not noise. */
    private val partNoise = Regex("""copy|копия|audio\s+only""", setOf(RegexOption.IGNORE_CASE))
    private const val WEB_ADDRESS =
        """(?:www\.)?[\p{L}\d-]+\.(?:com|net|org|ru|fm|me|info|biz|cc|to|io|tv|club|pro|su|by|ua|kz|de|uk|is|co|app)"""
    private val webAddress = Regex(WEB_ADDRESS, setOf(RegexOption.IGNORE_CASE))

    /** A converter site in front of the name: "y2mate.com - Title". */
    private val converterPrefix = Regex("""^$WEB_ADDRESS\s*(?:-|\u2013|\u2014|_)+\s*""", setOf(RegexOption.IGNORE_CASE))

    /** A video ID in square brackets, as yt-dlp names its files, with the space or underscores before it. */
    private val bracketedVideoId = Regex("""[\s_]*\[([A-Za-z0-9_-]{11})]""")

    /** A video ID joined to the title by a hyphen, as youtube-dl used to name its files: "Title-dQw4w9WgXcQ". */
    private val trailingVideoId = Regex("""(?<=[^\s-])-([A-Za-z0-9_-]{11})$""")

    /** "Title_audio only_" from downloaders that mark audio-only files, before the end or a bracket. */
    private val audioOnlyMarker = Regex("""_+audio[ _]only_*(?=$|[\s\[(])""", setOf(RegexOption.IGNORE_CASE))

    /** "Title_128kbps": a bitrate the converter put at the end, without brackets. */
    private val trailingBitrate = Regex("""[\s_-]+\d{2,3}\s*kbps$""", setOf(RegexOption.IGNORE_CASE))
    private val untitled = Regex(
        """(?:track|audio|aud|rec|recording|voice|trk|запись|новая\s+запись|трек)[\s_-]*\d*|aud-\d{8}-wa\d+|""" +
            """[0-9a-f]{16,}|\d+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** "1-03 Title": disc 1, track 3. */
    private val discAndTrack = Regex("""^\d{1,2}-(\d{1,3})\s+""")

    /** "01. Title", "7) Title", "01_Title", "12 - Title". */
    private val trackWithSeparator = Regex("""^(\d{1,3})\s*(?:[.)_]|\s-)\s*""")

    /** "03 Title": a leading zero marks a track number; "99 Red Balloons" stays a title. */
    private val zeroPaddedTrack = Regex("""^(0\d{1,2})\s+""")

    /** The release group's tag at the end of a scene-style name, such as "-grp". */
    private val sceneGroup = Regex("""[a-z0-9]{2,8}""")

    /** Returns null when the name carries no title, for example "AUD-20230101-WA0001.opus" or "Track 05.mp3". */
    fun parse(fileName: String): ParsedFileName? {
        var name = fileName.substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', "")
        if (extension.lowercase() in audioExtensions) name = name.dropLast(extension.length + 1)
        name = Spaces.plain(name.replace("%20", " "))
        // Before underscores turn into spaces: a video ID may contain underscores.
        name = dropDownloaderTraces(name)
        // "01-artist_name-song_title-grp": words joined by underscores, fields by bare hyphens.
        val sceneStyle = ' ' !in name && '_' in name
        if (' ' !in name) name = name.replace('_', ' ')
        name = bracketed.replace(name) { match ->
            if (isNoise(match.groupValues[1], bracketNoise)) "" else match.value
        }.trim()
        if (name.isEmpty() || untitled.matches(name)) return null

        var track: Int? = null
        for (pattern in listOf(discAndTrack, trackWithSeparator, zeroPaddedTrack)) {
            val match = pattern.find(name) ?: continue
            track = match.groupValues[1].toInt()
            name = name.substring(match.range.last + 1)
            break
        }

        var parts = name.split(separator).map(::clean).filter { it.isNotEmpty() }.toMutableList()
        if (sceneStyle && parts.size == 1 && '-' in name) {
            parts = name.split('-').map(::clean).filter { it.isNotEmpty() }.toMutableList()
            if (track == null && parts.size > 1 && trackNumber(parts[0]) != null) track = trackNumber(parts.removeAt(0))
            if (parts.size >= 3 && sceneGroup.matches(parts.last())) parts.removeAt(parts.lastIndex)
        }
        while (parts.size > 1 && isNoise(parts.last(), partNoise)) parts.removeAt(parts.lastIndex)

        val numbered = (1 until parts.size - 1).firstOrNull { trackNumber(parts[it]) != null }
        val (artist, title) = when {
            parts.isEmpty() -> return null
            numbered != null -> {
                track = trackNumber(parts[numbered])
                parts[0] to parts.drop(numbered + 1).joinToString(" - ")
            }
            parts.size == 1 -> null to parts[0]
            else -> parts[0] to parts.drop(1).joinToString(" - ")
        }
        // A bare number is a title only next to an artist ("Artist - 1999"); alone it says nothing.
        if (title.isEmpty() || (artist == null && untitled.matches(title))) return null
        return ParsedFileName(artist, title, track)
    }

    private fun dropDownloaderTraces(name: String): String {
        var result = bracketedVideoId.replace(name) { if (isVideoId(it.groupValues[1])) "" else it.value }
        result = trailingVideoId.replace(result) { if (isVideoId(it.groupValues[1])) "" else it.value }
        result = audioOnlyMarker.replace(result, "")
        result = trailingBitrate.replace(result, "")
        return converterPrefix.replace(result, "").trim()
    }

    /**
     * Eleven characters that look random: a digit, an underscore or a hyphen, or a capital after the first letter next
     * to small letters. A word such as "Unbreakable" is not an ID; one with a capital inside ("PlayStation") would be.
     */
    private fun isVideoId(text: String): Boolean = text.length == 11 && (
        text.any { it.isDigit() || it == '_' || it == '-' } ||
            (text.drop(1).any { it.isUpperCase() } && text.any { it.isLowerCase() })
        )

    /** One to three digits, 1 to 999; longer numbers are not track numbers. */
    private fun trackNumber(text: String): Int? =
        text.takeIf { it.length <= 3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..999 }

    private fun isNoise(text: String, words: Regex): Boolean {
        val trimmed = text.trim()
        return words.matches(trimmed) || webAddress.matches(trimmed)
    }

    private fun clean(text: String): String = text.trim().trim('-', '_').replace(Regex("""\s{2,}"""), " ").trim()
}
