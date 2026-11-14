package io.github.lorddetson.piplayer.library.details

/** The sections of track details in their order (docs/library.md §18.1). */
enum class DetailSection { SONG, PLAYS, FILE, COVER, IDS }

/**
 * A field of track details, in the order the screen shows it within its [section] (docs/library.md §18.1). M3 adds the
 * source of each field's value and a per-field revert; a row stays a field with a value so that it can.
 */
enum class DetailField(val section: DetailSection) {
    TITLE(DetailSection.SONG),
    ARTIST(DetailSection.SONG),
    ALBUM(DetailSection.SONG),
    ALBUM_ARTIST(DetailSection.SONG),
    TRACK(DetailSection.SONG),
    DISC(DetailSection.SONG),
    DISC_SUBTITLE(DetailSection.SONG),
    DATE(DetailSection.SONG),
    ORIGINAL_DATE(DetailSection.SONG),
    GENRE(DetailSection.SONG),
    COMPOSER(DetailSection.SONG),
    COMPILATION(DetailSection.SONG),
    LABEL(DetailSection.SONG),
    CATALOG_NUMBER(DetailSection.SONG),
    BARCODE(DetailSection.SONG),
    ISRC(DetailSection.SONG),
    TRACK_GAIN(DetailSection.SONG),
    ALBUM_GAIN(DetailSection.SONG),

    FAVORITE(DetailSection.PLAYS),
    PLAY_COUNT(DetailSection.PLAYS),
    LAST_PLAYED(DetailSection.PLAYS),

    FORMAT(DetailSection.FILE),
    CODEC(DetailSection.FILE),
    BITRATE(DetailSection.FILE),
    SAMPLE_RATE(DetailSection.FILE),
    CHANNELS(DetailSection.FILE),
    BIT_DEPTH(DetailSection.FILE),
    LENGTH(DetailSection.FILE),
    INDEX_LENGTH(DetailSection.FILE),
    SIZE(DetailSection.FILE),
    FILE_NAME(DetailSection.FILE),
    FOLDER(DetailSection.FILE),
    MIME_TYPE(DetailSection.FILE),
    TAG_ENCODING(DetailSection.FILE),
    ADDED(DetailSection.FILE),
    MODIFIED(DetailSection.FILE),
    TAGS(DetailSection.FILE),

    COVER_SOURCE(DetailSection.COVER),
    COVER_SIZE(DetailSection.COVER),

    RECORDING_ID(DetailSection.IDS),
    RELEASE_ID(DetailSection.IDS),
    RELEASE_GROUP_ID(DetailSection.IDS),
    TRACK_ID(DetailSection.IDS),
    ARTIST_ID(DetailSection.IDS),
    ALBUM_ARTIST_ID(DetailSection.IDS),
}

/** Where the length shown comes from (docs/library.md §18.2). */
enum class LengthSource {
    /** The header, final. */
    HEADER,

    /** A walk over all frames, final. */
    WALK,

    /** The header, until the walks have checked FLAC's last frame. */
    HEADER_UNCHECKED,

    /** The media index, before or without a length of PiPlayer's own. */
    INDEX,

    /** TagLib's estimate. */
    ESTIMATE,
}

/** Where the cover shown comes from (docs/library.md §18.3). */
enum class CoverSource { SONG_OWN, ALBUM_OWN, EMBEDDED, THUMBNAIL, NONE }

/** Why the tags of a file are not read (docs/library.md §5.3). */
enum class TagState { NOT_READ, UNSUPPORTED, FAILED, CANNOT_READ }

/** The kinds of MusicBrainz IDs, by the path of their page (docs/library.md §18.4). */
enum class MusicBrainzKind(val path: String) {
    RECORDING("recording"),
    RELEASE("release"),
    RELEASE_GROUP("release-group"),
    TRACK("track"),
    ARTIST("artist"),
}

/** A value of track details, apart from its label and its format. */
sealed interface DetailValue {
    data class Text(val text: String) : DetailValue

    /** Several values of one field, such as genres. */
    data class Texts(val texts: List<String>) : DetailValue

    /** A track or disc number with the total when known. */
    data class OfTotal(val number: Int, val total: Int?) : DetailValue

    data class Flag(val on: Boolean) : DetailValue

    data class Count(val count: Long) : DetailValue

    /** A moment, in milliseconds since the epoch. */
    data class Time(val epochMs: Long) : DetailValue

    data class Length(val ms: Long, val source: LengthSource?) : DetailValue

    data class Bytes(val bytes: Long) : DetailValue

    data class Kbps(val kbps: Int) : DetailValue

    data class Hertz(val hz: Int) : DetailValue

    data class Channels(val count: Int) : DetailValue

    data class Bits(val bits: Int) : DetailValue

    /** A ReplayGain value in dB with its peak. */
    data class Gain(val db: Double, val peak: Double?) : DetailValue

    /** A folder on a volume. */
    data class Folder(val volume: String, val relativePath: String) : DetailValue

    data class Tags(val state: TagState) : DetailValue

    data class Cover(val source: CoverSource) : DetailValue

    /** A picture's size in pixels, and in bytes when known. */
    data class Pixels(val width: Int, val height: Int, val bytes: Long?) : DetailValue

    /** A MusicBrainz ID; [url] is its page, null for an ID that is not well-formed. */
    data class MusicBrainz(val kind: MusicBrainzKind, val id: String) : DetailValue {
        val url: String? get() = MusicBrainzLinks.url(kind, id)
    }
}

/** One row: a field and its value. A field may have several rows, such as one per artist ID. */
data class DetailRow(val field: DetailField, val value: DetailValue)

/**
 * What the details of a track show (docs/library.md §18): its [title] and [artist] for the header, and its rows in field
 * order. [fileId] is its file in the library, null for a file opened from another app.
 */
data class TrackDetails(val fileId: Long?, val title: String, val artist: String?, val album: String?, val rows: List<DetailRow>) {

    /** The rows by section, in section order, without the empty ones. */
    val sections: List<Pair<DetailSection, List<DetailRow>>>
        get() = rows.groupBy { it.field.section }.toSortedMap().map { it.key to it.value }

    /** The values of [field]. */
    fun values(field: DetailField): List<DetailValue> = rows.filter { it.field == field }.map { it.value }

    /** The single value of [field], or null. */
    fun value(field: DetailField): DetailValue? = values(field).firstOrNull()
}

/** The pages of MusicBrainz IDs (docs/library.md §18.4). */
object MusicBrainzLinks {

    private val wellFormed = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** The page of [id], or null when it is not a well-formed ID. */
    fun url(kind: MusicBrainzKind, id: String): String? =
        id.takeIf { wellFormed.matches(it) }?.let { "$BASE${kind.path}/$it" }

    private const val BASE = "https://musicbrainz.org/"
}
