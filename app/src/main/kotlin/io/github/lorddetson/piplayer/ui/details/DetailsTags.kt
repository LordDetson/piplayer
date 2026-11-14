package io.github.lorddetson.piplayer.ui.details

import io.github.lorddetson.piplayer.library.details.DetailField

/** Test tags of the details screen (docs/library.md §18). */
object DetailsTags {
    const val LIST = "details_list"

    /** The rows of [field]. */
    fun row(field: DetailField) = "details_${field.name.lowercase()}"
}
