package io.github.lorddetson.piplayer.library.values

/** A file's tags as typed values, precedence level 3 (TI §7.1, §10.1). */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val albumArtists: List<String> = emptyList(),
    val titleSort: String? = null,
    val artistSort: String? = null,
    val albumSort: String? = null,
    val albumArtistSort: String? = null,
    val composers: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val date: String? = null,
    val originalDate: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val discSubtitle: String? = null,
    val compilation: Boolean = false,
    val label: String? = null,
    val catalogNumber: String? = null,
    val barcode: String? = null,
    val isrc: String? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val releaseGroupMbid: String? = null,
    val releaseTrackMbid: String? = null,
    val artistMbids: List<String> = emptyList(),
    val albumArtistMbids: List<String> = emptyList(),
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
) {
    companion object {
        /** What the media index and some taggers write for "no value". */
        private const val UNKNOWN = "<unknown>"
        private val trueValues = setOf("1", "true", "yes")
        private val listSeparator = Regex("""\s*;\s*""")
        private val leadingNumber = Regex("""^[+-]?\d+(?:[.,]\d+)?""")

        /** The values of TagLib's property map. */
        fun from(properties: Map<String, List<String>>): TrackTags {
            fun values(key: String): List<String> =
                properties[key].orEmpty().map { it.trim() }.filter { it.isNotEmpty() && it != UNKNOWN }

            fun single(key: String): String? = values(key).firstOrNull()

            // Written joined by "; " where a format holds one value (TI §7.5).
            fun list(key: String): List<String> =
                values(key).flatMap { it.split(listSeparator) }.filter { it.isNotEmpty() }.distinct()

            fun number(key: String): Double? = single(key)?.let { value ->
                leadingNumber.find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
            }

            val artistValues = values("ARTIST")
            val artists = list("ARTISTS").ifEmpty { artistValues }
            // Some taggers of Vorbis comments write these spellings, and TagLib passes them on as they are.
            val albumArtistValues = values("ALBUMARTIST").ifEmpty { values("ALBUM ARTIST") }
            val albumArtists = list("ALBUMARTISTS").ifEmpty { albumArtistValues }
            val track = Position.of(single("TRACKNUMBER"))
            val disc = Position.of(single("DISCNUMBER"))
            return TrackTags(
                title = single("TITLE"),
                artist = artistValues.ifEmpty { artists }.joinOrNull(),
                artists = artists,
                album = single("ALBUM"),
                albumArtist = albumArtistValues.ifEmpty { albumArtists }.joinOrNull(),
                albumArtists = albumArtists,
                titleSort = single("TITLESORT"),
                artistSort = single("ARTISTSORT"),
                albumSort = single("ALBUMSORT"),
                albumArtistSort = single("ALBUMARTISTSORT"),
                composers = list("COMPOSER"),
                genres = list("GENRE"),
                date = single("DATE"),
                originalDate = single("ORIGINALDATE"),
                trackNumber = track.number,
                trackTotal = Position.count(single("TRACKTOTAL")) ?: Position.count(single("TOTALTRACKS")) ?: track.total,
                discNumber = disc.number,
                discTotal = Position.count(single("DISCTOTAL")) ?: Position.count(single("TOTALDISCS")) ?: disc.total,
                discSubtitle = single("DISCSUBTITLE"),
                compilation = single("COMPILATION")?.lowercase() in trueValues,
                label = single("LABEL"),
                catalogNumber = single("CATALOGNUMBER"),
                barcode = single("BARCODE"),
                isrc = single("ISRC"),
                recordingMbid = single("MUSICBRAINZ_TRACKID"),
                releaseMbid = single("MUSICBRAINZ_ALBUMID"),
                releaseGroupMbid = single("MUSICBRAINZ_RELEASEGROUPID"),
                releaseTrackMbid = single("MUSICBRAINZ_RELEASETRACKID"),
                artistMbids = values("MUSICBRAINZ_ARTISTID"),
                albumArtistMbids = values("MUSICBRAINZ_ALBUMARTISTID"),
                trackGainDb = number("REPLAYGAIN_TRACK_GAIN"),
                trackPeak = number("REPLAYGAIN_TRACK_PEAK"),
                albumGainDb = number("REPLAYGAIN_ALBUM_GAIN"),
                albumPeak = number("REPLAYGAIN_ALBUM_PEAK"),
            )
        }

        private fun List<String>.joinOrNull(): String? = if (isEmpty()) null else joinToString("; ")
    }

    /** A track or disc position as `n` or `n/total`. */
    private class Position(val number: Int?, val total: Int?) {
        companion object {
            fun of(value: String?): Position {
                if (value == null) return Position(null, null)
                return Position(count(value.substringBefore('/')), count(value.substringAfter('/', "")))
            }

            /** A positive whole number; "A1" or 0 says nothing. */
            fun count(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
