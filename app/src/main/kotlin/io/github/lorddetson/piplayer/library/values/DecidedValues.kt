package io.github.lorddetson.piplayer.library.values

import io.github.lorddetson.piplayer.settings.ProtectedField

/** How a decided part's value reads as text (TI §10.2): what the precedence resolver and `decided_mirror` hold. */
enum class PartKind {
    /** Text as it is. */
    TEXT,

    /** Several texts joined by [DecidedField.SEPARATOR]. */
    LIST,

    /** A credit: each credited name followed by its join phrase, all joined by [DecidedField.SEPARATOR]. */
    CREDITS,

    /** A whole number in decimal digits. */
    NUMBER,

    /** `1` or `0`. */
    FLAG,

    /** An MBID in its usual form. */
    MBID,

    /** MBIDs joined by [DecidedField.SEPARATOR]. */
    MBID_LIST,

    /** The ID of a `saved_cover` in decimal digits. */
    COVER,
}

/**
 * A part of a field of the protected-fields list (TI §7.1, §10.2): a field is decided as a whole, with all its parts, so
 * the parts of two decisions never mix. [code] is what the user database stores, [stored] the name `decided_mirror` and
 * `release_choice.values` use. [release] parts of an identification live in its `release_choice`, [perDisc] ones there
 * under their disc, and [decisionColumn] ones in the decision row; the others are `track_metadata` rows.
 */
enum class DecidedField(
    val code: Int,
    val stored: String,
    val group: ProtectedField,
    val kind: PartKind,
    val release: Boolean = false,
    val perDisc: Boolean = false,
    val decisionColumn: Boolean = false,
) {
    TITLE(1, "title", ProtectedField.TITLE, PartKind.TEXT),
    ARTIST(2, "artist", ProtectedField.ARTIST, PartKind.TEXT),
    ARTIST_CREDITS(3, "artist_credits", ProtectedField.ARTIST, PartKind.CREDITS),
    ARTIST_SORT(4, "artist_sort", ProtectedField.ARTIST, PartKind.TEXT),
    ALBUM(5, "album", ProtectedField.ALBUM, PartKind.TEXT, release = true),
    ALBUM_ARTIST(6, "album_artist", ProtectedField.ALBUM_ARTIST, PartKind.TEXT, release = true),
    ALBUM_ARTIST_CREDITS(7, "album_artist_credits", ProtectedField.ALBUM_ARTIST, PartKind.CREDITS, release = true),
    ALBUM_ARTIST_SORT(8, "album_artist_sort", ProtectedField.ALBUM_ARTIST, PartKind.TEXT, release = true),
    DATE(9, "date", ProtectedField.DATE, PartKind.TEXT, release = true),
    ORIGINAL_DATE(10, "original_date", ProtectedField.ORIGINAL_DATE, PartKind.TEXT, release = true),
    TRACK_NUMBER(11, "track_number", ProtectedField.TRACK_NUMBER, PartKind.NUMBER),
    TRACK_TOTAL(12, "track_total", ProtectedField.TRACK_NUMBER, PartKind.NUMBER, release = true, perDisc = true),
    DISC_NUMBER(13, "disc_number", ProtectedField.DISC_NUMBER, PartKind.NUMBER),
    DISC_TOTAL(14, "disc_total", ProtectedField.DISC_NUMBER, PartKind.NUMBER, release = true),
    DISC_SUBTITLE(15, "disc_subtitle", ProtectedField.DISC_SUBTITLE, PartKind.TEXT, release = true, perDisc = true),
    COMPILATION(16, "compilation", ProtectedField.COMPILATION, PartKind.FLAG, release = true),
    LABEL(17, "label", ProtectedField.LABEL, PartKind.LIST, release = true),
    CATALOG_NUMBER(18, "catalog_number", ProtectedField.CATALOG_NUMBER, PartKind.LIST, release = true),
    BARCODE(19, "barcode", ProtectedField.BARCODE, PartKind.TEXT, release = true),
    ISRC(20, "isrc", ProtectedField.ISRC, PartKind.LIST),
    RECORDING_MBID(21, "recording_mbid", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID, decisionColumn = true),
    RELEASE_MBID(22, "release_mbid", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID, release = true),
    RELEASE_GROUP_MBID(23, "release_group_mbid", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID, release = true),
    RELEASE_TRACK_MBID(24, "release_track_mbid", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID, decisionColumn = true),
    ARTIST_IDS(25, "artist_ids", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID_LIST),
    ALBUM_ARTIST_IDS(26, "album_artist_ids", ProtectedField.MUSICBRAINZ_IDS, PartKind.MBID_LIST, release = true),
    COVER(27, "cover", ProtectedField.COVER, PartKind.COVER, release = true),
    ;

    companion object {
        /** What joins the items of a list and of a credit (TI §10.2). */
        const val SEPARATOR = '\u001F'

        private val byCode = entries.associateBy { it.code }
        private val byName = entries.associateBy { it.stored }

        fun ofCode(code: Int): DecidedField? = byCode[code]

        fun ofName(name: String): DecidedField? = byName[name]

        // Once: the resolver asks for the parts of every field of every track that a whole run of the mirror resolves.
        private val byGroup = entries.groupBy { it.group }

        /** The parts of [field], in code order. */
        fun partsOf(field: ProtectedField): List<DecidedField> = byGroup[field].orEmpty()

        /** The items of a list or credit value. */
        fun items(value: String?): List<String> = if (value.isNullOrEmpty()) emptyList() else value.split(SEPARATOR)

        /** A list value of [items]; null when there are none. */
        fun list(items: List<String>): String? = items.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())

        /** A credit value of names and their join phrases; null without names. */
        fun credits(names: List<Pair<String, String>>): String? =
            list(names.flatMap { (name, join) -> listOf(name, join) })

        /** The credited names of a credit value. */
        fun creditedNames(value: String?): List<String> = items(value).filterIndexed { i, _ -> i % 2 == 0 }

        /** A credit value as printed: each name followed by its join phrase. */
        fun printed(value: String?): String = items(value).joinToString("")
    }
}

/** The kinds of decision (TI §10.2), with the code the user database stores. */
enum class DecisionKind(val code: Int) {
    APPLY(1),
    EDIT(2),
    REVERT_FIELD(3),
    KEEP_AS_IS(4),
    SKIP(5),
    ;

    /** The precedence level of the values this kind stores (TI §7.1); null for kinds that store none. */
    val level: Int?
        get() = when (this) {
            APPLY -> LEVEL_IDENTIFICATION
            EDIT -> LEVEL_USER_EDIT
            else -> null
        }

    companion object {
        /** Precedence level 1: a user edit (TI §7.1). */
        const val LEVEL_USER_EDIT = 1

        /** Precedence level 2: a confirmed identification. */
        const val LEVEL_IDENTIFICATION = 2

        /** Level 3: the file's tags as PiPlayer reads them. */
        const val LEVEL_TAGS = 3

        /** Level 4: the media index, before the tag pass has read the file. */
        const val LEVEL_INDEX = 4

        /** Level 5: the file-name guess. */
        const val LEVEL_FILE_NAME = 5

        private val byCode = entries.associateBy { it.code }

        fun ofCode(code: Int): DecisionKind? = byCode[code]
    }
}

/** A decided value (TI §7.1): [value] null is an empty value; [level] 1 or 2; [decisionId] the decision it comes from. */
data class Decided(val value: String?, val level: Int, val decisionId: Long)

/**
 * One decision of a track as the resolver reads it (TI §7.1): its [fields] (the ticked, edited or reverted fields of
 * the protected-fields list) and the [values] of their parts, a missing or null value being empty. An [undone] decision
 * counts for nothing. [id] orders the decisions; [createdAt] is for track details and the history only.
 */
data class DecisionValues(
    val id: Long,
    val kind: DecisionKind,
    val createdAt: Long,
    val fields: Set<ProtectedField>,
    val values: Map<DecidedField, String?> = emptyMap(),
    val undone: Boolean = false,
)

/** Levels 1 and 2 of the precedence (TI §7.1): which decision wins each part of each field. */
object DecisionResolver {

    /**
     * Newest first: by row ID, the order of the commits. The time a decision was made orders nothing, so a clock set
     * back cannot make a new decision lose to an older one (TI §7.1).
     */
    val NEWEST_FIRST: Comparator<DecisionValues> = compareByDescending { it.id }

    /**
     * The winners of [decisions]: per part, the newest decision that is not undone and sets or reverts the part's field.
     * A revert hides the field, so no part of it is decided and the file's value wins; Keep as-is and Skip set none.
     */
    fun resolve(decisions: List<DecisionValues>): Map<DecidedField, Decided> {
        val live = decisions.filter { !it.undone && (it.kind.level != null || it.kind == DecisionKind.REVERT_FIELD) }
            .sortedWith(NEWEST_FIRST)
        if (live.isEmpty()) return emptyMap()
        val result = LinkedHashMap<DecidedField, Decided>()
        for (field in ProtectedField.entries) {
            val winner = live.firstOrNull { field in it.fields } ?: continue
            // A revert hides the field: nothing older counts, and the file's value wins.
            val level = winner.kind.level ?: continue
            for (part in DecidedField.partsOf(field)) {
                result[part] = Decided(winner.values[part]?.takeIf { it.isNotEmpty() }, level, winner.id)
            }
        }
        return result
    }

    /**
     * The badge state of [decided] (TI §6, §10.1): edited when a level-1 value wins, identified when only level-2 values
     * do, from tags otherwise.
     */
    fun badgeOf(decided: Map<DecidedField, Decided>): Int = when {
        decided.values.any { it.level == DecisionKind.LEVEL_USER_EDIT } -> BADGE_EDITED
        decided.isNotEmpty() -> BADGE_IDENTIFIED
        else -> BADGE_NONE
    }

    /** No decided value wins: the values come from the file. */
    const val BADGE_NONE = 0

    /** Values of a confirmed identification win, and no edit does. */
    const val BADGE_IDENTIFIED = 1

    /** A value of the user's own edit wins. */
    const val BADGE_EDITED = 2
}
