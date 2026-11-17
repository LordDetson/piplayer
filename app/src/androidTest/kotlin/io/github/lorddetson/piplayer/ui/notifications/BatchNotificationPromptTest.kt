package io.github.lorddetson.piplayer.ui.notifications

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The explanation before a batch asks for the notification permission (TI §8, PIM-21): the batch waits for the answer
 * of the explanation and of the system dialog, and starts either way. The system dialog itself is a checklist item.
 */
@RunWith(AndroidJUnit4::class)
class BatchNotificationPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext

    private fun text(id: Int) = context.getString(id)

    /** A prompt on API 36 whose permission is [granted] and was [asked] for already; its calls are counted. */
    private class CountingPrompt(granted: Boolean, asked: Boolean, sdk: Int = 36) {
        var started = 0
        var askedNow = 0
        var marked = 0
        val prompt = BatchNotificationPrompt(
            sdk = sdk,
            granted = { granted },
            asked = { asked },
            markAsked = { marked++ },
            ask = { askedNow++ },
        )
    }

    @Test
    fun theExplanationComesBeforeTheFirstBatchAndAllowAsksForThePermission() {
        val counting = CountingPrompt(granted = false, asked = false)
        counting.prompt.beforeBatch { counting.started++ }
        compose.setContent { BatchNotificationExplanation(counting.prompt) }

        // The batch waits: one explanation, no system dialog yet.
        assertThat(counting.started).isZero()
        assertThat(counting.prompt.explaining).isTrue()
        compose.onNodeWithTag(BatchNotificationTags.EXPLANATION).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.batch_notification_explanation)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.batch_notification_explanation_allow)).performClick()
        compose.waitForIdle()

        // Allow brings up the system dialog, and the prompt is not asked again.
        assertThat(counting.askedNow).isEqualTo(1)
        assertThat(counting.marked).isEqualTo(1)
        assertThat(counting.prompt.explaining).isFalse()
        compose.onNodeWithTag(BatchNotificationTags.EXPLANATION).assertDoesNotExist()

        // Whatever the dialog answered, the held batch starts.
        counting.prompt.answered()
        assertThat(counting.started).isEqualTo(1)
    }

    @Test
    fun notNowStartsTheBatchWithoutThePermission() {
        val counting = CountingPrompt(granted = false, asked = false)
        counting.prompt.beforeBatch { counting.started++ }
        compose.setContent { BatchNotificationExplanation(counting.prompt) }

        compose.onNodeWithText(text(R.string.batch_notification_explanation_skip)).performClick()
        compose.waitForIdle()

        assertThat(counting.started).isEqualTo(1)
        assertThat(counting.askedNow).isZero()
        assertThat(counting.marked).isEqualTo(1)
        compose.onNodeWithTag(BatchNotificationTags.EXPLANATION).assertDoesNotExist()
    }

    @Test
    fun aGrantedPermissionStartsWithNoExplanation() {
        val counting = CountingPrompt(granted = true, asked = false)
        compose.setContent { BatchNotificationExplanation(counting.prompt) }

        counting.prompt.beforeBatch { counting.started++ }

        assertThat(counting.started).isEqualTo(1)
        assertThat(counting.prompt.explaining).isFalse()
        compose.onNodeWithTag(BatchNotificationTags.EXPLANATION).assertDoesNotExist()
    }

    @Test
    fun aBatchStartedAfterANoRunsWithoutAskingAgain() {
        val counting = CountingPrompt(granted = false, asked = true)
        compose.setContent { BatchNotificationExplanation(counting.prompt) }

        counting.prompt.beforeBatch { counting.started++ }

        assertThat(counting.started).isEqualTo(1)
        assertThat(counting.askedNow).isZero()
    }

    @Test
    fun aDeviceBeforeAndroid13HasNothingToAsk() {
        val counting = CountingPrompt(granted = false, asked = false, sdk = 32)

        counting.prompt.beforeBatch { counting.started++ }

        assertThat(counting.started).isEqualTo(1)
        assertThat(counting.prompt.explaining).isFalse()
    }
}
