package io.github.lorddetson.piplayer.ui.details

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagFileFormat
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualTagEditorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun previewsTheExactOldAndNewValuesAndWarnsBeforeWritingWav() {
        val changes = mapOf("TITLE" to TagChange(listOf("Original title"), listOf("Edited title")))
        val requests = mutableListOf<Unit>()
        compose.setContent {
            PiPlayerTheme {
                ManualTagPreview(changes, TagFileFormat.WAV, busy = false, onBack = {}) { requests += Unit }
            }
        }
        val context = TestFiles.targetContext
        compose.onNodeWithText(context.getString(R.string.tag_history_transition, "Original title", "Edited title")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manual_tag_wav_notice)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manual_tag_write)).performClick()
        compose.runOnIdle { assertThat(requests).hasSize(1) }
    }

    @Test
    fun theManualPreviewOffersSavingInPiPlayer() {
        val changes = mapOf("TITLE" to TagChange(listOf("Original title"), listOf("Edited title")))
        var saved = false
        var written = false
        compose.setContent {
            PiPlayerTheme {
                ManualTagPreview(changes, TagFileFormat.ID3V24, busy = false, onBack = {}, onSave = { saved = true }) { written = true }
            }
        }

        compose.onNodeWithTag("manual-tag-save-in-player").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertThat(saved).isTrue()
            assertThat(written).isFalse()
        }
    }

    @Test
    fun anId3v1RemovalIsShownSeparatelyFromOtherFileTags() {
        compose.setContent {
            PiPlayerTheme {
                ManualTagPreview(emptyMap(), TagFileFormat.ID3V24, busy = false, onBack = {}, legacyId3Policy = Id3v1WritePolicy.REMOVE) {}
            }
        }
        val context = TestFiles.targetContext
        compose.onNodeWithText(context.getString(R.string.tag_writing_id3v1)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tag_writing_id3v1_preview_remove)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manual_tag_write)).assertIsDisplayed()
    }

    @Test
    fun submitsOnlyEditedTagsAndDefaultsToKeepingExistingGenreAndComment() {
        val original = mapOf(
            "TITLE" to listOf("Original title"), "ARTIST" to listOf("First artist", "Second artist"),
            "COMMENT" to listOf("Curated comment"), "GENRE" to listOf("Soul"), "REPLAYGAIN_TRACK_GAIN" to listOf("-8 dB"),
        )
        val requests = mutableListOf<Pair<Map<String, List<String>>, Set<String>>>()
        compose.setContent {
            PiPlayerTheme {
                ManualTagForm(original) { changes, fills -> requests += changes to fills }
            }
        }
        compose.onNodeWithTag("manual-tag-TITLE").performTextReplacement("Edited title")
        compose.onNodeWithText(TestFiles.targetContext.getString(R.string.manual_tag_write)).performClick()

        compose.runOnIdle {
            assertThat(requests).containsExactly(mapOf("TITLE" to listOf("Edited title")) to setOf("GENRE", "COMMENT"))
        }
    }
}
