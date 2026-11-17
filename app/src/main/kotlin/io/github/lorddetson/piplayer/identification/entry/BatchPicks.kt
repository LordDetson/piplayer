package io.github.lorddetson.piplayer.identification.entry

import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision

/**
 * A track of Songs missing information with its Keep as-is or Skip (TI §1), [missingInfo] as `MissingInfoDecision` reads
 * the track's `missing_info_decision`, `MissingInfoDecision.NONE` for a track the user has decided nothing about.
 */
data class BatchTrack(val fileId: Long, val missingInfo: Int = MissingInfoDecision.NONE) {

    /** Whether the user has decided about this track, with Keep as-is or with Skip: a batch over the list leaves it out (TI §1). */
    val decided: Boolean get() = missingInfo != MissingInfoDecision.NONE
}

/**
 * The files a batch takes (TI §1 "Entry points"): what the entry point shows, each file once and in the shown order.
 * Both pickers keep the order so that a batch reads its albums in the order the user sees them.
 */
object BatchPicks {

    /** The files of [fileIds] in their order, each once: the songs of a selection or the tracks of a folder. */
    fun files(fileIds: List<Long>): List<Long> = fileIds.distinct()

    /**
     * The files of Songs missing information, [tracks] in the list's order, each once, without the tracks marked Keep as-is
     * or Skip (TI §1). Keep as-is has already taken its track out of the list; a Skip leaves it listed, so a batch over the
     * list leaves it out here.
     */
    fun missingInfo(tracks: List<BatchTrack>): List<Long> = tracks.filterNot { it.decided }.map { it.fileId }.distinct()
}
