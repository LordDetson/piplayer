package io.github.lorddetson.piplayer.ui.fix

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.db.BatchEntity
import io.github.lorddetson.piplayer.ui.BottomBarTags
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import io.github.lorddetson.piplayer.ui.search.SearchTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The Fix tab and the bottom bar (TI §8, PIM-18): the batches of the library database shown with their counts, the
 * "Needs attention" entry and the ETA placeholder, and the bar's items switching the places.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FixTabTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { FakeMediaIndex() }).around(compose)

    private fun text(id: Int) = compose.activity.getString(id)

    private fun quantity(id: Int, quantity: Int) = compose.activity.resources.getQuantityString(id, quantity, quantity)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher)[0].performClick()
    }

    /** The batches the Fix tab shows, in their queue order: the active ones and one of each final status. */
    private fun seedBatches() {
        val batches = listOf(
            BatchEntity(uuid = "b-queued", createdAt = 1000, source = BatchEntity.SOURCE_SELECTION, status = BatchEntity.STATUS_QUEUED),
            BatchEntity(uuid = "b-processing", createdAt = 2000, source = BatchEntity.SOURCE_FOLDER, status = BatchEntity.STATUS_PROCESSING),
            BatchEntity(uuid = "b-review", createdAt = 3000, source = BatchEntity.SOURCE_MISSING_INFO, status = BatchEntity.STATUS_REVIEW),
            BatchEntity(uuid = "b-done", createdAt = 4000, source = BatchEntity.SOURCE_FOLDER, status = BatchEntity.STATUS_DONE),
            BatchEntity(uuid = "b-cancelled", createdAt = 5000, source = BatchEntity.SOURCE_SELECTION, status = BatchEntity.STATUS_CANCELLED),
        )
        runBlocking {
            Library.read(TestFiles.targetContext) { database ->
                runBlocking { batches.forEach { database.batches().save(it) } }
            }
        }
    }

    @Test
    fun theFixTabShowsTheBatchesTheirCountsAndTheNeedsAttentionEntry() {
        seedBatches()
        tap(hasTestTag(BottomBarTags.FIX))
        waitFor(hasText(text(R.string.fix_needs_attention)))

        // The review queue's entry: one batch waits for the review cards of the next step.
        compose.onNodeWithText(quantity(R.plurals.fix_review_waiting, 1)).assertIsDisplayed()

        // The counts by status and each batch's status label.
        waitFor(hasTestTag(FixTags.LIST))
        for (status in listOf(
            R.string.batch_status_queued,
            R.string.batch_status_processing,
            R.string.batch_status_review,
            R.string.batch_status_done,
            R.string.batch_status_cancelled,
        )) {
            waitFor(hasText(text(status), substring = true))
            compose.onAllNodes(hasText(text(status), substring = true))[0].assertIsDisplayed()
        }

        // The ETA placeholder of the processing batch.
        compose.onNodeWithText(text(R.string.fix_eta_not_measured), substring = true).assertIsDisplayed()
    }

    @Test
    fun theBottomBarSwitchesBetweenTheLibrarySearchAndFix() {
        // An empty library: the Fix tab still works, and the places switch.
        waitFor(hasTestTag(LibraryTags.TABS))

        tap(hasTestTag(BottomBarTags.SEARCH))
        waitFor(hasTestTag(SearchTags.FIELD))

        tap(hasTestTag(BottomBarTags.FIX))
        waitFor(hasText(text(R.string.fix_needs_attention)))

        tap(hasTestTag(BottomBarTags.LIBRARY))
        waitFor(hasTestTag(LibraryTags.TABS))
    }

    @Test
    fun aCompletedBatchOpensTheFileTagScreen() {
        val uuid = "a1c3bbda-a5d3-42ac-b433-428aa9fed33d"
        Library.read(TestFiles.targetContext) { database ->
            runBlocking {
                database.batches().save(BatchEntity(uuid, 1000, BatchEntity.SOURCE_SELECTION, BatchEntity.STATUS_DONE))
            }
        }
        tap(hasTestTag(BottomBarTags.FIX))
        tap(hasText(text(R.string.batch_tag_write_open)))
        waitFor(hasText(text(R.string.batch_tag_write_title)))
        waitFor(hasText(text(R.string.batch_tag_write_empty)))
    }
}
