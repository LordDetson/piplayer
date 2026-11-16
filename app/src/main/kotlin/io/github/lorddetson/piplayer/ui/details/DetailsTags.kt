package io.github.lorddetson.piplayer.ui.details

import io.github.lorddetson.piplayer.library.details.DetailField

/** Test tags of the details screen (docs/library.md §18). */
object DetailsTags {
    const val LIST = "details_list"
    const val HEADER = "details_header"

    /** The rows of [field]. */
    fun row(field: DetailField) = "details_${field.name.lowercase()}"

    /** "Revert to the file's value" of [field]'s first row (docs/library.md §18.7). */
    fun revert(field: DetailField) = "details_revert_${field.name.lowercase()}"

    /** "Keep PiPlayer's value" of [field] changed in the file. */
    fun keep(field: DetailField) = "details_keep_${field.name.lowercase()}"

    /** "Use the file's value" of [field] changed in the file. */
    fun useFile(field: DetailField) = "details_use_file_${field.name.lowercase()}"

    const val IDENTIFICATION = "details_identification"
    const val UNDO_IDENTIFICATION = "details_undo_identification"
    const val RESET = "details_reset"
    const val TRACE = "details_trace"
}
