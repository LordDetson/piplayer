package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Library folders while the first sync still runs or after it failed, as after a restore of the user data onto a new
 * device: a rule is not offered for removal as one whose folder is gone (docs/library.md §3.6).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FoldersBeforeTheFirstSyncTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)
    private val gate = CountDownLatch(1)
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, "Alpha", path = "Music/Rock/"))
        this.gate = this@FoldersBeforeTheFirstSyncTest.gate
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    @After
    fun openTheGate() = gate.countDown()

    @Test
    fun aRuleIsNotTakenForOneWhoseFolderIsGoneBeforeTheLibraryIsKnown() {
        runBlocking { UserData.folderRules(context).set(FakeMediaIndex.PRIMARY, "Music/Rock/", include = false) }
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription(text(R.string.settings_open))).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_library_folders)), timeoutMillis = 5_000)
        compose.onNodeWithText(text(R.string.settings_library_folders)).performScrollTo().performClick()
        // The screen has its state: no folder is known yet.
        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_empty)), timeoutMillis = 5_000)
        assertThat(gate.count).describedAs("the first sync is held").isEqualTo(1)

        compose.onNodeWithText(text(R.string.folders_not_found)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.folders_remove)).assertDoesNotExist()

        gate.countDown()

        compose.waitUntilExactlyOneExists(hasText("Rock"), timeoutMillis = 10_000)
        compose.onNodeWithText(text(R.string.folders_excluded)).assertExists()
        compose.onNodeWithText(text(R.string.folders_not_found)).assertDoesNotExist()
    }

    @Test
    fun aRuleIsNotTakenForOneWhoseFolderIsGoneAfterAFailedFirstSync() {
        index.failingVolume = FakeMediaIndex.PRIMARY
        index.failure = IllegalStateException("The index went away")
        gate.countDown()
        runBlocking {
            UserData.folderRules(context).set(FakeMediaIndex.PRIMARY, "Music/Rock/", include = false)
            Library.sync(context).awaitIdle()
        }
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription(text(R.string.settings_open))).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_library_folders)), timeoutMillis = 5_000)
        compose.onNodeWithText(text(R.string.settings_library_folders)).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_empty)), timeoutMillis = 5_000)

        compose.onNodeWithText(text(R.string.folders_not_found)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.folders_remove)).assertDoesNotExist()

        index.failure = null
        Library.sync(context).request()

        compose.waitUntilExactlyOneExists(hasText("Rock"), timeoutMillis = 10_000)
        compose.onNodeWithText(text(R.string.folders_excluded)).assertExists()
        compose.onNodeWithText(text(R.string.folders_not_found)).assertDoesNotExist()
    }
}
