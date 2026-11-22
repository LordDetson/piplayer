package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabLayout
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
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
                it.remove(THEME)
                it.remove(SELECTED_THEME)
                it.remove(SYSTEM_CANVAS)
                it.clearPanelOverrides()
            }
        }

        override fun after() = runBlocking<Unit> {
            SettingsFiles.edit(file) {
                before[ORDER]?.let { order -> it[ORDER] = order } ?: it.remove(ORDER)
                before[HIDDEN]?.let { hidden -> it[HIDDEN] = hidden } ?: it.remove(HIDDEN)
                before[THEME]?.let { theme -> it[THEME] = theme } ?: it.remove(THEME)
                before[SELECTED_THEME]?.let { theme -> it[SELECTED_THEME] = theme } ?: it.remove(SELECTED_THEME)
                before[SYSTEM_CANVAS]?.let { canvas -> it[SYSTEM_CANVAS] = canvas } ?: it.remove(SYSTEM_CANVAS)
                it.clearPanelOverrides()
                for ((key, value) in before.asMap()) {
                    if (key.name.startsWith("library_panel_") && value is String) {
                        it[stringPreferencesKey(key.name)] = value
                    }
                }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(resetLayout).around(compose)

    @Test
    fun anEditStartedFromTheToolbarAlsoChangesLibraryTabsAndSurvivesRecreation() {
        val songs = tab(R.string.tab_songs)
        val sort = action(R.string.library_sort)
        val originalSongs = songs.fetchSemanticsNode().boundsInRoot
        val originalSort = sort.fetchSemanticsNode().boundsInRoot
        sort.performTouchInput { longClick() }
        compose.onNodeWithTag("ui_edit_mode").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("ui_canvas"), timeoutMillis = 5_000)
        compose.onNodeWithTag("ui_component_tab:albums").performTouchInput { click() }
        compose.onNodeWithTag("ui_edit_hide").performClick()
        compose.onNodeWithTag("ui_edit_done").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("ui_edit_done"), timeoutMillis = 5_000)

        compose.activityRule.scenario.recreate()
        compose.waitUntilExactlyOneExists(tabMatcher(R.string.tab_songs), timeoutMillis = 10_000)
        tab(R.string.tab_albums).assertDoesNotExist()
        assertThat(songs.fetchSemanticsNode().boundsInRoot).isEqualTo(originalSongs)
        assertThat(sort.fetchSemanticsNode().boundsInRoot).isEqualTo(originalSort)
    }

    @Test
    fun aTabCanHideItsTextAndRemainAccessibleAfterRecreation() {
        val songs = tab(R.string.tab_songs)
        songs.performTouchInput { longClick() }
        compose.onNodeWithTag("ui_edit_tab_label").performClick()
        compose.onNodeWithTag("ui_tab_label_icon_only").performClick()
        compose.waitUntilDoesNotExist(tabMatcher(R.string.tab_songs), timeoutMillis = 5_000)
        compose.activityRule.scenario.recreate()
        val iconOnly = hasAnyAncestor(hasTestTag(LibraryTags.TABS)) and hasContentDescription(text(R.string.tab_songs))
        compose.waitUntilExactlyOneExists(iconOnly, timeoutMillis = 10_000)
        compose.onNode(iconOnly).assertIsSelected().performTouchInput { longClick() }
        compose.onNodeWithTag("ui_edit_tab_label").performClick()
        compose.onNodeWithTag("ui_tab_label_icon_and_text").performClick()
        compose.waitUntilAtLeastOneExists(tabMatcher(R.string.tab_songs), timeoutMillis = 5_000)
    }

    @Test
    fun toolbarButtonsMoveHideAndReturnWithoutOpeningSettings() {
        val search = action(R.string.library_search)
        val sort = action(R.string.library_sort)
        sort.performTouchInput { longClick() }
        compose.onNodeWithTag("library_button_move").performClick()
        compose.onNodeWithTag("library_button_move_to_search").performClick()
        compose.waitUntil(5_000) { sort.fetchSemanticsNode().boundsInRoot.left < search.fetchSemanticsNode().boundsInRoot.left }

        sort.performTouchInput { longClick() }
        compose.onNodeWithTag("library_button_hide").performClick()
        compose.waitUntilDoesNotExist(hasContentDescription(text(R.string.library_sort)), timeoutMillis = 5_000)
        compose.onNodeWithTag("library_panel_add").performClick()
        compose.onNodeWithTag("library_button_restore_sort").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription(text(R.string.library_sort)), timeoutMillis = 5_000)
        compose.waitUntil(5_000) { sort.fetchSemanticsNode().boundsInRoot.left < search.fetchSemanticsNode().boundsInRoot.left }
        sort.performClick()
        compose.onNode(hasText(text(R.string.sort_reverse))).assertExists()
    }

    @Test
    fun settingsCanBeRestoredWhenAllToolbarButtonsAreHidden() {
        for (id in listOf(R.string.library_search, R.string.library_sort, R.string.settings_open)) {
            action(id).performTouchInput { longClick() }
            compose.onNodeWithTag("library_button_hide").performClick()
            compose.waitUntilDoesNotExist(hasContentDescription(text(id)), timeoutMillis = 5_000)
        }
        compose.onNodeWithTag("library_panel_add").performClick()
        compose.onNodeWithTag("library_button_restore_settings").performClick()
        compose.waitUntilAtLeastOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 5_000)
        action(R.string.settings_open).performClick()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.settings_theme)), timeoutMillis = 5_000)
    }

    @Test
    fun selectingAThemeRestoresBothPartsOfItsPanel() {
        action(R.string.library_search).performTouchInput { longClick() }
        compose.onNodeWithTag("library_button_hide").performClick()
        compose.waitUntilDoesNotExist(hasContentDescription(text(R.string.library_search)), timeoutMillis = 5_000)
        tab(R.string.tab_albums).performTouchInput { longClick() }
        compose.onNodeWithTag("library_tab_hide").performClick()
        compose.waitUntilDoesNotExist(tabMatcher(R.string.tab_albums), timeoutMillis = 5_000)

        val appearance = AppearanceSettings(TestFiles.targetContext)
        runBlocking { appearance.setTheme(ThemeMode.DARK) }
        compose.waitUntilAtLeastOneExists(tabMatcher(R.string.tab_albums), timeoutMillis = 5_000)
        action(R.string.library_search).assertExists()
        runBlocking { appearance.setTheme(ThemeMode.SYSTEM) }
        compose.waitUntilDoesNotExist(tabMatcher(R.string.tab_albums), timeoutMillis = 5_000)
        action(R.string.library_search).assertDoesNotExist()
    }

    @Test
    fun longPressMovesHidesAndRestoresTabsOnTheLibraryStrip() {
        tab(R.string.tab_songs).assertIsSelected()
        tab(R.string.tab_albums).performTouchInput { longClick() }
        compose.onNodeWithTag("library_tab_move").performClick()
        compose.onNodeWithTag("library_tab_move_to_songs").performClick()
        awaitLayout { it.visible.take(2) == listOf("albums", "songs") }
        tab(R.string.tab_songs).assertIsSelected()

        tab(R.string.tab_songs).performTouchInput { longClick() }
        compose.onNodeWithTag("library_tab_hide").performClick()
        awaitLayout { "songs" in it.hidden }
        tab(R.string.tab_songs).assertDoesNotExist()
        tab(R.string.tab_albums).assertIsSelected()

        compose.activityRule.scenario.recreate()
        compose.waitUntilAtLeastOneExists(tabMatcher(R.string.tab_albums), timeoutMillis = 10_000)
        tab(R.string.tab_songs).assertDoesNotExist()
        tab(R.string.tab_albums).performTouchInput { longClick() }
        compose.onNodeWithTag("library_tab_add").performClick()
        compose.onNodeWithTag("library_tab_restore_songs").performClick()
        awaitLayout { it.visible.take(2) == listOf("albums", "songs") }
        tab(R.string.tab_albums).assertIsSelected()
        compose.onNodeWithTag("library_tab_editor").assertDoesNotExist()
    }

    @Test
    fun theLastVisibleTabCannotBeHiddenAndCanRestoreAnotherTab() {
        runBlocking {
            for (id in LibraryTabLayout.DEFAULT.order.filterNot { it == "songs" }) {
                LibraryTabsSettings(file).setVisible(id, false)
            }
        }
        compose.waitUntilDoesNotExist(tabMatcher(R.string.tab_albums), timeoutMillis = 10_000)
        tab(R.string.tab_songs).performTouchInput { longClick() }
        compose.onNodeWithTag("library_tab_hide").assertDoesNotExist()
        compose.onNodeWithTag("library_tab_add").performClick()
        compose.onNodeWithTag("library_tab_restore_albums").performClick()
        awaitLayout { it.visible == listOf("songs", "albums") }
        tab(R.string.tab_songs).assertIsSelected()
    }

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

    private fun action(id: Int) = compose.onNode(hasContentDescription(text(id)))

    private fun MutablePreferences.clearPanelOverrides() {
        asMap().keys.filter { it.name.startsWith("library_panel_") }.forEach { remove(it) }
    }

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
        val THEME = stringPreferencesKey("theme_mode")
        val SELECTED_THEME = stringPreferencesKey("selected_custom_theme_id")
        val SYSTEM_CANVAS = stringPreferencesKey("ui_canvas_system")
    }
}
