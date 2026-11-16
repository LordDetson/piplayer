package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lorddetson.piplayer.settings.ProtectedField

/** Test tags of the review (TI §6, review screen A). */
object ReviewTags {
    const val PROGRESS = "review_progress"
    const val CARD = "review_card"
    const val CONFIDENCE = "review_confidence"
    const val REASON = "review_reason"
    const val COVER_LINE = "review_cover_line"

    fun line(field: ProtectedField) = "review_line_${field.stored}"
}

/** The review of [model] (TI §6, review screen A); [onBack] closes it. */
@Composable
fun ReviewContent(model: ReviewModel, onBack: () -> Unit, modifier: Modifier = Modifier) = Unit
