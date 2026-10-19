package io.github.lorddetson.piplayer.spike

import io.github.lorddetson.piplayer.identification.score.TrackHint
import io.github.lorddetson.piplayer.identification.text.FileNameParser

/** Where the title of a text search came from. */
enum class HintSource { TAGS, FILE_NAME, NONE }

data class SpikeHint(val hint: TrackHint, val source: HintSource)

/** Builds the text-search hint of the spike from TagLib's property map and the file name (TI §3.3). */
object SpikeHints {

    /**
     * Tags win: title, artist, album and track number come from TITLE, ARTIST, ALBUM and TRACKNUMBER ("3/11" is
     * track 3), several values joined by ", ". Without a title tag the file name supplies the title, and the artist and
     * track number where the tags lack them. Blank values count as missing.
     */
    fun from(tags: Map<String, List<String>>, fileName: String?, durationMs: Long?): SpikeHint {
        fun tag(key: String) = tags[key].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.joinToString(", ")

        val title = tag("TITLE")
        val artist = tag("ARTIST")
        val album = tag("ALBUM")
        val trackNumber = tag("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull()
        if (title != null) return SpikeHint(TrackHint(title, artist, durationMs, album, trackNumber), HintSource.TAGS)

        val parsed = fileName?.let(FileNameParser::parse)
            ?: return SpikeHint(TrackHint(null, artist, durationMs, album, trackNumber), HintSource.NONE)
        return SpikeHint(
            TrackHint(parsed.title, artist ?: parsed.artist, durationMs, album, trackNumber ?: parsed.trackNumber),
            HintSource.FILE_NAME,
        )
    }
}
