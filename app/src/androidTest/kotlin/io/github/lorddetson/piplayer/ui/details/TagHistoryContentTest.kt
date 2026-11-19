package io.github.lorddetson.piplayer.ui.details

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.tagging.TagTarget
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagWriteHistoryEntry
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecovery
import io.github.lorddetson.piplayer.tagging.write.TagWriteTarget
import io.github.lorddetson.piplayer.ui.fix.BatchTagUndoSummary
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
    fun theBatchSummaryCountsOriginalChangesAndShowsUndoConflicts() {
        val context = TestFiles.targetContext
        val written = TagWriteHistoryEntry(
            id = 1L, displayName = "sample.mp3", at = 1L, undoneAt = 2L, undoOf = null,
            changes = emptyMap(), conflicts = emptySet(),
        )
        val reversal = written.copy(id = 2L, at = 2L, undoOf = 1L, undoneAt = null, conflicts = setOf("TITLE"))
        val current = written.copy(id = 3L, displayName = "another.mp3", undoneAt = null)
        val requests = mutableListOf<Unit>()
        compose.setContent {
            PiPlayerTheme {
                BatchTagUndoSummary(listOf(current, reversal, written), eligible = 1, busy = false) { requests += Unit }
            }
        }
        compose.onNodeWithText(context.getString(R.string.batch_tag_undo_counts, 2, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.batch_tag_undo_conflict, "sample.mp3", "TITLE")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.batch_tag_undo_start)).performClick()
        compose.runOnIdle { assertThat(requests).hasSize(1) }
    }

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

    @Test
    fun interruptedWritesShowTheirSavedDiffAndOfferAResumeAction() {
        val context = TestFiles.targetContext
        val target = TagWriteTarget(
            Uri.parse("content://media/external_primary/audio/media/14"), TagTarget.LibraryFile(22),
            identityId = 9, decisionId = null, batchUuid = null, volume = "external_primary", relativePath = "Music/",
            displayName = "interrupted.mp3", contentKey = "01".repeat(32),
        )
        val recovery = TagWriteRecovery(
            5, 22, target, failed = false, changes = mapOf("TITLE" to TagChange(listOf("Old"), listOf("New"))),
        )
        val resumed = mutableListOf<TagWriteRecovery>()
        compose.setContent {
            PiPlayerTheme {
                TagHistoryContent(emptyList(), recoveries = listOf(recovery), onResume = { resumed += it })
            }
        }
        compose.onNodeWithText(target.displayName).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_recovery_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_history_transition, "Old", "New")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_recovery_resume)).performClick()
        compose.runOnIdle { assertThat(resumed).containsExactly(recovery) }
    }
}
