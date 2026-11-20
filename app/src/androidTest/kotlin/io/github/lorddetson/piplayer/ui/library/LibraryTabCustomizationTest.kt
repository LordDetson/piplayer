package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryTabCustomizationTest {

    private val compose = createAndroidComposeRule<MainActivity>()
    private val file get() = SettingsFiles.settings(TestFiles.targetContext)

    private val resetLayout = object : ExternalResource() {
        private lateinit var before: Preferences

        override fun before() = runBlocking<Unit> {
            before = file.data.first()
            SettingsFiles.edit(file) {
                it.remove(ORDER)
                it.remove(HIDDEN)
            }
        }

        override fun after() = runBlocking<Unit> {
            SettingsFiles.edit(file) {
                before[ORDER]?.let { order -> it[ORDER] = order } ?: it.remove(ORDER)
                before[HIDDEN]?.let { hidden -> it[HIDDEN] = hidden } ?: it.remove(HIDDEN)
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(resetLayout).around(compose)

    @Test
    fun aDragAndHideSurviveRecreatingTheActivityAndRestoreKeepsTheActiveTab() {
        tab(R.string.tab_songs).assertIsSelected()
        openEditor()

        val row = compose.onNodeWithTag("library_tab_editor_row_albums", useUnmergedTree = true)
        val height = row.fetchSemanticsNode().size.height.toFloat()
        compose.onNodeWithTag("library_tab_editor_handle_albums", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(24) { moveBy(Offset(0f, -1.25f * height / 24)) }
            up()
        }
        awaitLayout { it.order.take(2) == listOf("albums", "songs") }
        toggleSongs()
        awaitLayout { "songs" in it.hidden }
        closeEditor()
        tab(R.string.tab_songs).assertDoesNotExist()
        tab(R.string.tab_albums).assertIsSelected()

        compose.activityRule.scenario.recreate()
        compose.waitUntilAtLeastOneExists(tabMatcher(R.string.tab_albums), timeoutMillis = 10_000)
        tab(R.string.tab_songs).assertDoesNotExist()
        tab(R.string.tab_albums).assertIsSelected()
        openEditor()
        toggleSongs()
        awaitLayout { it.visible.take(2) == listOf("albums", "songs") }
        closeEditor()

        tab(R.string.tab_songs).assertExists()
        tab(R.string.tab_albums).assertIsSelected()
    }

    @Test
    fun anAccessibleMoveChangesTheOrderAndKeepsAnAlreadyVisibleSelection() {
        tab(R.string.tab_albums).performClick()
        openEditor()

        compose.onNodeWithTag("library_tab_editor_row_artists", useUnmergedTree = true)
            .performCustomAccessibilityActionWithLabel(text(R.string.library_tabs_move_up))
        awaitLayout { it.order.take(3) == listOf("songs", "artists", "albums") }
        closeEditor()

        tab(R.string.tab_albums).assertIsSelected()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun tabMatcher(id: Int) = hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS))

    private fun tab(id: Int) = compose.onNode(tabMatcher(id))

    private fun openEditor() {
        val open = hasContentDescription(text(R.string.library_tabs_manage))
        compose.waitUntilAtLeastOneExists(open, timeoutMillis = 10_000)
        compose.onNode(open).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("library_tab_editor"), timeoutMillis = 10_000)
    }

    private fun closeEditor() {
        compose.onNode(hasContentDescription(text(R.string.library_tabs_close))).performClick()
        compose.waitUntilDoesNotExist(hasTestTag("library_tab_editor"), timeoutMillis = 10_000)
    }

    private fun toggleSongs() = compose.onNode(hasContentDescription(text(R.string.library_tabs_show, text(R.string.tab_songs))))
        .performClick()

    private fun awaitLayout(check: (LibraryTabLayout) -> Boolean) = compose.waitUntil(10_000) {
        check(runBlocking { LibraryTabsSettings(file).layout.first() })
    }

    private companion object {
        val ORDER = stringPreferencesKey("library_tab_order")
        val HIDDEN = stringSetPreferencesKey("library_hidden_tabs")
    }
}
