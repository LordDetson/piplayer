package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.userdata.UserDatabase

/** What the smart lists share (docs/library.md §17). */
object SmartLists {
    /** How many songs Recently added, Recently played and Most played hold at most (§17.1). */
    const val RANKED_LIMIT = 100
}

/**
 * The reasons of Songs missing information as a set of bits, the values `MergedValues` stores in
 * `track_display.missing_info_reasons` (TI §1, docs/library.md §17.1).
 */
object MissingReasons {
    const val TITLE = MergedValues.TITLE_FROM_FILE_NAME
    const val ARTIST = MergedValues.NO_ARTIST
    const val ALBUM = MergedValues.NO_ALBUM
    const val COVER = MergedValues.NO_COVER
    const val RECORDING_ID = MergedValues.NO_RECORDING_ID

    /** Every reason. */
    const val ALL = TITLE or ARTIST or ALBUM or COVER or RECORDING_ID

    /** The reasons that are on by default: cover and MusicBrainz ID are not (§17.1). */
    const val DEFAULT = TITLE or ARTIST or ALBUM

    /** The reasons in the order the chips and the rows show them. */
    val ORDER = listOf(TITLE, ARTIST, ALBUM, COVER, RECORDING_ID)

    /**
     * Every stored set of reasons that holds one of [chosen], in ascending order: the values a list looks up in the index on
     * the reasons (§17.4).
     */
    fun storedSets(chosen: Int): List<Int> = (1..ALL).filter { it and chosen != 0 }
}

/**
 * Keep as-is and Skip as Songs missing information reads them (TI §1, §10.1): the codes of
 * `track_display.missing_info_decision` and the values of the `decided_mirror` row they come from.
 */
object MissingInfoDecision {
    const val NONE = 0
    const val KEEP_AS_IS = 1
    const val SKIPPED = 2

    /** The field of the `decided_mirror` row that holds a track's Keep as-is or Skip; no part of a decided field. */
    const val FIELD = "missing_info_decision"
    const val KEEP_AS_IS_VALUE = "keep_as_is"
    const val SKIPPED_VALUE = "skipped"

    /** The code of a row's [value]; a value this build does not know counts as none. */
    fun ofValue(value: String?): Int = when (value) {
        KEEP_AS_IS_VALUE -> KEEP_AS_IS
        SKIPPED_VALUE -> SKIPPED
        else -> NONE
    }

    /** The row value of [code]; none has no row. */
    fun valueOf(code: Int): String? = when (code) {
        KEEP_AS_IS -> KEEP_AS_IS_VALUE
        SKIPPED -> SKIPPED_VALUE
        else -> null
    }
}

/**
 * The own covers that take "No cover" away (docs/library.md §17.1): the album keys with an own cover and the content keys
 * (as the library database writes them) of the identities with one.
 */
data class OwnCoverKeys(val albumKeys: Set<String> = emptySet(), val contentKeys: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = albumKeys.isEmpty() && contentKeys.isEmpty()

    companion object {
        val NONE = OwnCoverKeys()

        /** The own covers that [user] holds now. */
        suspend fun read(user: UserDatabase): OwnCoverKeys {
            val covers = user.covers()
            return OwnCoverKeys(covers.coveredAlbumKeys().toSet(), covers.coveredContentKeys().mapTo(HashSet(), Hex::text))
        }
    }
}
