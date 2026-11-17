package io.github.lorddetson.piplayer.identification.entry

import io.github.lorddetson.piplayer.library.db.BatchEntity

/**
 * What starts a batch (TI §1 "Entry points"): the songs of a selection, the tracks of a folder, or the whole list of
 * Songs missing information with the reasons it shows. [source] is the way the `batch` row records it (TI §8).
 */
sealed interface BatchRequest {

    /** What the batch's `source` column holds. */
    val source: String

    /** "Identify" of selected songs, by file ID, in the order the list shows them. */
    data class Selection(val fileIds: List<Long>) : BatchRequest {
        override val source: String get() = BatchEntity.SOURCE_SELECTION
    }

    /**
     * "Identify" of a folder, over its tracks and those of the folders below it, as the folder page's "Play all, with
     * subfolders" plays them.
     */
    data class Folder(val volume: String, val relativePath: String) : BatchRequest {
        override val source: String get() = BatchEntity.SOURCE_FOLDER
    }

    /** "Identify" of Songs missing information, with [reasons] the set of reasons the list was filtered by (TI §1). */
    data class MissingInfo(val reasons: Int) : BatchRequest {
        override val source: String get() = BatchEntity.SOURCE_MISSING_INFO
    }
}
