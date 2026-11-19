package io.github.lorddetson.piplayer.ui.details

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagWriteHistoryEntry
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagHistoryContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHistoryShowsTagValuesAndNamesTheFieldsAlreadyChangedElsewhere() {
        val context = TestFiles.targetContext
        val entry = TagWriteHistoryEntry(
            id = 21, displayName = "sample.mp3", at = 1_700_000_000_000L,
            undoneAt = null, undoOf = 4,
            changes = mapOf("ALBUM" to TagChange(listOf("Edited album"), listOf("Original album"))),
            conflicts = setOf("TITLE"),
        )
        val choices = mutableListOf<Long>()
        compose.setContent { PiPlayerTheme { TagHistoryContent(listOf(entry), onUndo = { choices += it }) } }

        compose.onNodeWithText(entry.displayName).assertIsDisplayed()
        compose.onNodeWithText("ALBUM").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_history_transition, "Edited album", "Original album")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_history_conflicts, "TITLE")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_history_undo)).performClick()
        compose.runOnIdle { assertThat(choices).containsExactly(entry.id) }
    }
}
