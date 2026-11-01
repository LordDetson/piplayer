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
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.LibrarySongSource
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.FolderRuleEntity
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Library section of the Settings screen and the Library folders screen (docs/library.md §3.4 to §3.6). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibrarySettingsScreenTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, "Alpha", path = "Music/Rock/"), row(2, "Bravo", path = "Music/Jazz/"))
    }
    private val detachedVolume = "1234-5678"
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    // The library rule also starts each test without folder rules and with the default hidden files.
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    private fun openSettings() {
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription(text(R.string.settings_open))).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_library)), timeoutMillis = 5_000)
    }

    private fun openFolders() {
        openSettings()
        compose.onNodeWithText(text(R.string.settings_library_folders)).performScrollTo().performClick()
    }

    private fun rules() = runBlocking { UserData.folderRules(context).all() }

    private fun listedTitles() = Library.read(context) { LibrarySongSource(it, HiddenFiles()).songs() }.map { it.title }

    /** Waits for the sync that a change of the rules asks for. */
    private fun awaitListed(vararg titles: String) {
        val until = System.currentTimeMillis() + 10_000
        while (listedTitles() != titles.toList() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertThat(listedTitles()).containsExactly(*titles)
    }

    @Test
    fun theHiddenFileSettingsAreWrittenAtOnce() {
        openSettings()
        val settings = LibrarySettings(context)

        compose.onNodeWithText(text(R.string.settings_show_system_sounds)).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { settings.readHiddenFiles().showSystemSounds }

        compose.onNodeWithText(text(R.string.settings_show_recordings)).performClick()
        compose.waitUntil(5_000) { settings.readHiddenFiles().showRecordings }

        compose.onNodeWithText(context.getString(R.string.settings_min_length_value, 10)).assertExists()
        compose.onNodeWithText(text(R.string.settings_min_length)).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_min_length_off)), timeoutMillis = 5_000)
        compose.onNodeWithText(text(R.string.settings_min_length_off)).performClick()
        compose.waitUntil(5_000) { settings.readHiddenFiles().minLengthSeconds == 0 }
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_min_length_off)), timeoutMillis = 5_000)
    }

    @Test
    fun aFolderExcludedInLibraryFoldersLeavesTheListsAndCanFollowItsParentAgain() {
        awaitListed("Alpha", "Bravo")
        openFolders()
        compose.waitUntilExactlyOneExists(hasText("Jazz"), timeoutMillis = 10_000)
        compose.onNodeWithText("Music").assertExists()
        compose.onNodeWithText("Rock").assertExists()

        compose.onNodeWithText("Jazz").performClick()
        compose.onNodeWithText(text(R.string.folders_exclude)).performClick()

        compose.waitUntil(5_000) { rules().isNotEmpty() }
        assertThat(rules()).containsExactly(FolderRuleEntity(FakeMediaIndex.PRIMARY, "Music/Jazz/", FolderRuleEntity.EXCLUDE))
        awaitListed("Alpha")
        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_excluded)), timeoutMillis = 5_000)

        compose.onNodeWithText("Jazz").performClick()
        compose.onNodeWithText(text(R.string.folders_follow)).performClick()

        compose.waitUntil(5_000) { rules().isEmpty() }
        awaitListed("Alpha", "Bravo")
    }

    @Test
    fun anExcludedFolderWithAnIncludedFolderInsideKeepsOnlyThatOne() {
        awaitListed("Alpha", "Bravo")
        openFolders()
        compose.waitUntilExactlyOneExists(hasText("Rock"), timeoutMillis = 10_000)

        // "Only these folders", one level down.
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText(text(R.string.folders_exclude)).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_excluded)), timeoutMillis = 5_000)
        compose.onNodeWithText("Rock").performClick()
        compose.onNodeWithText(text(R.string.folders_include)).performClick()

        compose.waitUntil(5_000) { rules().size == 2 }
        assertThat(rules()).containsExactlyInAnyOrder(
            FolderRuleEntity(FakeMediaIndex.PRIMARY, "Music/", FolderRuleEntity.EXCLUDE),
            FolderRuleEntity(FakeMediaIndex.PRIMARY, "Music/Rock/", FolderRuleEntity.INCLUDE),
        )
        awaitListed("Alpha")
        compose.onNodeWithText(text(R.string.folders_excluded_above)).assertExists()
    }

    @Test
    fun rulesOfAVolumeThatIsNotAttachedAreListedAndCanBeRemoved() {
        runBlocking { UserData.folderRules(context).set(detachedVolume, "Music/", include = false) }
        openFolders()

        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_not_found)), timeoutMillis = 10_000)
        compose.onNodeWithText(context.getString(R.string.folders_not_found_excluded, detachedVolume)).assertExists()

        compose.onNodeWithText(text(R.string.folders_remove)).performScrollTo().performClick()

        compose.waitUntil(5_000) { rules().isEmpty() }
        compose.waitUntilDoesNotExist(hasText(text(R.string.folders_not_found)), timeoutMillis = 5_000)
    }

    @Test
    fun aRuleForAFolderThatHoldsNoAudioNowIsListedAndCanBeRemoved() {
        awaitListed("Alpha", "Bravo")
        runBlocking { UserData.folderRules(context).set(FakeMediaIndex.PRIMARY, "Old/", include = false) }
        openFolders()

        compose.waitUntilExactlyOneExists(hasText(text(R.string.folders_not_found)), timeoutMillis = 10_000)
        compose.onNodeWithText("Old/").assertExists()

        compose.onNodeWithText(text(R.string.folders_remove)).performScrollTo().performClick()

        compose.waitUntil(5_000) { rules().isEmpty() }
    }
}
