package io.github.lorddetson.piplayer.library.details

import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.identification.score.Reason
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.Decisions

/** The sections of track details in their order (docs/library.md §18.1). */
enum class DetailSection { SONG, PLAYS, FILE, COVER, IDS }

/**
 * A field of track details, in the order the screen shows it within its [section] (docs/library.md §18.1); [protected]
 * is the field of the protected-fields list its value belongs to, which a decision may decide (§18.7, TI §7.1).
 */
enum class DetailField(val section: DetailSection, val protected: ProtectedField? = null) {
    TITLE(DetailSection.SONG, ProtectedField.TITLE),
    ARTIST(DetailSection.SONG, ProtectedField.ARTIST),
    ALBUM(DetailSection.SONG, ProtectedField.ALBUM),
    ALBUM_ARTIST(DetailSection.SONG, ProtectedField.ALBUM_ARTIST),
    TRACK(DetailSection.SONG, ProtectedField.TRACK_NUMBER),
    DISC(DetailSection.SONG, ProtectedField.DISC_NUMBER),
    DISC_SUBTITLE(DetailSection.SONG, ProtectedField.DISC_SUBTITLE),
    DATE(DetailSection.SONG, ProtectedField.DATE),
    ORIGINAL_DATE(DetailSection.SONG, ProtectedField.ORIGINAL_DATE),
    GENRE(DetailSection.SONG),
    COMPOSER(DetailSection.SONG),
    COMPILATION(DetailSection.SONG, ProtectedField.COMPILATION),
    LABEL(DetailSection.SONG, ProtectedField.LABEL),
    CATALOG_NUMBER(DetailSection.SONG, ProtectedField.CATALOG_NUMBER),
    BARCODE(DetailSection.SONG, ProtectedField.BARCODE),
    ISRC(DetailSection.SONG, ProtectedField.ISRC),
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

    COVER_SOURCE(DetailSection.COVER, ProtectedField.COVER),
    COVER_SIZE(DetailSection.COVER),

    RECORDING_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),
    RELEASE_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),
    RELEASE_GROUP_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),
    TRACK_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),
    ARTIST_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),
    ALBUM_ARTIST_ID(DetailSection.IDS, ProtectedField.MUSICBRAINZ_IDS),

    /** The AcoustID track ID of the decision that wins the recording ID (§18.7); not a field of the protected list. */
    ACOUSTID_ID(DetailSection.IDS),
}

/**
 * Where a value comes from (docs/library.md §18.7): a precedence [level] of TI §7.1 (`DecisionKind.LEVEL_*`, 1 to 5),
 * with the [provider] of an identification at level 2.
 */
data class ValueSource(val level: Int, val provider: ProviderId? = null)

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

/**
 * Where the cover shown comes from (docs/library.md §18.3): [SAVED] is a decided cover saved in PiPlayer, [CAA] the lazy
 * Cover Art Archive cover (§13.1).
 */
enum class CoverSource { SONG_OWN, ALBUM_OWN, SAVED, EMBEDDED, THUMBNAIL, CAA, NONE }

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

    /** An AcoustID track ID; [url] is its page, null for an ID that is not well-formed (§18.7). */
    data class AcoustId(val id: String) : DetailValue {
        val url: String? get() = AcoustIdLinks.url(id)
    }

    /** A decided value that is empty and that no lower level fills (§18.7). */
    data object Empty : DetailValue
}

/**
 * One row: a field and its value. A field may have several rows, such as one per artist ID. [source] is where the value
 * comes from (§18.7), null for rows without one; [decided] says that a decision of level 1 or 2 decides the row's field
 * of the protected-fields list, which a revert reverts; [changedInFile] that the file changed that field since.
 */
data class DetailRow(
    val field: DetailField,
    val value: DetailValue,
    val source: ValueSource? = null,
    val decided: Boolean = false,
    val changedInFile: Boolean = false,
)

/** The states of the metadata badge in M3 (TI §6, §10.1), by their `badge_state` codes. */
enum class BadgeState(val code: Int) {
    /** No decided value wins: the values come from the file. */
    FROM_TAGS(0),

    /** Values of a confirmed identification win, and no edit does; saved in PiPlayer. */
    IDENTIFIED(1),

    /** A value of the user's own edit wins; saved in PiPlayer. */
    EDITED(2),
    ;

    companion object {
        /** The state of [code]; a code this build does not know is none. */
        fun ofCode(code: Int?): BadgeState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The metadata badge (TI §6, docs/library.md §18.7): its [state] and, while identified, the [provider] and [confidence]
 * of the newest identification whose values win.
 */
data class Badge(val state: BadgeState, val provider: ProviderId? = null, val confidence: Double? = null)

/**
 * The newest identification of a track that is not undone (TI §7.1), which "Undo identification" undoes: its decision,
 * provenance, [reason] (TI §4.1), when it was made and when its values were fetched, in milliseconds since the epoch.
 */
data class IdentificationFacts(
    val decisionId: Long,
    val provider: ProviderId?,
    val confidence: Double?,
    val reason: Reason?,
    val createdAt: Long,
    val fetchedAt: Long?,
)

/** The Keep as-is or Skip of a track that counts (TI §1), which details can reset. */
data class ListDecision(val decisionId: Long, val skipped: Boolean)

/**
 * One run of the identification trace (TI §10.1, docs/library.md §18.7): its start, its [outcome] code (`found=high`,
 * `no_network`, ...), the retry time it reported, and its steps in order.
 */
data class TraceRun(val startedAt: Long, val outcome: String?, val retryAt: Long?, val steps: List<TraceStep>)

/** One step of a traced run: a stage code with what the trace holds of it, [skipped] and [failed] as codes. */
data class TraceStep(
    val stage: String,
    val provider: String? = null,
    val httpStatus: Int? = null,
    val resultCount: Int? = null,
    val bestScore: Double? = null,
    val elapsedMs: Long? = null,
    val skipped: String? = null,
    val failed: String? = null,
)

/**
 * What the decisions of track details act on (docs/library.md §18.7): the song's identity with its content key in hex,
 * and the file's place with its values now, for "Keep PiPlayer's value".
 */
data class DecisionTarget(val identityId: Long, val contentKey: String, val location: Decisions.LocationValues)

/**
 * What the details of a track show (docs/library.md §18): its [title] and [artist] for the header, and its rows in field
 * order. [fileId] is its file in the library, null for a file opened from another app. From M3 (§18.7): the metadata
 * [badge], the newest [identification] not undone, the Keep as-is or Skip that counts, the [trace] newest first, and the
 * [target] of the screen's decisions, null without an identity.
 */
data class TrackDetails(
    val fileId: Long?,
    val title: String,
    val artist: String?,
    val album: String?,
    val rows: List<DetailRow>,
    val badge: Badge? = null,
    val identification: IdentificationFacts? = null,
    val listDecision: ListDecision? = null,
    val trace: List<TraceRun> = emptyList(),
    val target: DecisionTarget? = null,
) {

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

/** The pages of AcoustID track IDs (docs/library.md §18.7). */
object AcoustIdLinks {

    private val wellFormed = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** The page of [id], or null when it is not a well-formed ID. */
    fun url(id: String): String? = id.takeIf { wellFormed.matches(it) }?.let { "https://acoustid.org/track/$it" }
}
