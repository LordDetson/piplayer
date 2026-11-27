package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.ManualTagValues
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiBuiltInGesture
import io.github.lorddetson.piplayer.settings.UiGesture
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import io.github.lorddetson.piplayer.settings.UiGestureBinding
import io.github.lorddetson.piplayer.settings.UiGestureEnvironment
import io.github.lorddetson.piplayer.settings.UiGestureProfile
import io.github.lorddetson.piplayer.settings.UiGestureSlot
import io.github.lorddetson.piplayer.settings.UiGestureTarget
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiSurfaceGestures
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabsSettings
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.withStockPlayerTheme
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CustomLibraryTabsTest {
    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/Tree/", genre = "Rock"),
            row(2, "Bravo", genre = "Pop"),
            row(3, "Charlie", path = "Music/Tree/Child/", genre = "Rock"),
        )
        volume("test_card").rows = listOf(row(4, "Echo", volume = "test_card", path = "Music/Tree/"))
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    @Test
    fun aRuleTabRequiresAValidRuleAndRestoresTheSelectedFilteredList() = withStockPlayerTheme(context, "library_", "custom_library_tab_") {
        openCreate()
        tap("custom_tab_source_rules")
        compose.onNode(hasTestTag("custom_tab_name")).performTextInput("Rock tracks")
        compose.onNode(hasTestTag("custom_tab_save")).assertIsNotEnabled()
        tap("custom_tab_rule_field_0")
        tap("custom_tab_rule_genre_0")
        compose.onNode(hasTestTag("custom_tab_rule_value_0")).performTextInput("rock")
        compose.onNode(hasTestTag("custom_tab_save")).assertIsEnabled().performClick()

        val songs = hasAnyAncestor(hasTestTag("custom_tab_songs"))
        compose.waitUntilExactlyOneExists(songs and hasText("Alpha"), timeoutMillis = 10_000)
        compose.onNode(songs and hasText("Charlie")).assertExists()
        compose.onNode(songs and hasText("Bravo")).assertDoesNotExist()

        compose.activityRule.scenario.recreate()
        compose.waitUntilExactlyOneExists(songs and hasText("Alpha"), timeoutMillis = 10_000)
        compose.onNode(hasAnyAncestor(hasTestTag(LibraryTags.TABS)) and hasText("Rock tracks")).assertIsSelected()
    }

    @Test
    fun aPlaylistTabPlaysTheSecondOccurrenceAndBecomesMissingAfterDeletion() = withStockPlayerTheme(context, "library_", "custom_library_tab_") {
        val oldName = "Repeatable playlist"
        val playlists = UserData.playlists(context)
        val playlist = makePlaylist(oldName, listOf(1L, 2L, 1L))
        openCreate()
        tap("custom_tab_source_playlist")
        compose.onNode(hasTestTag("custom_tab_name")).performTextInput("Repeats")
        tap("custom_tab_select_source")
        compose.waitUntilExactlyOneExists(hasText(oldName), timeoutMillis = 10_000)
        compose.onNode(hasText(oldName)).performClick()
        tap("custom_tab_save")

        val alpha = hasAnyAncestor(hasTestTag("custom_tab_songs")) and hasText("Alpha")
        compose.waitUntil(10_000) { compose.onAllNodes(alpha).fetchSemanticsNodes().size == 2 }
        val controller = connectController()
        try {
            compose.onAllNodes(alpha).assertCountEquals(2)[1].performClick()
            controller.waitUntil("the second Alpha occurrence is current in the displayed queue") {
                currentMediaItemIndex == 2 &&
                    List(mediaItemCount) { getMediaItemAt(it).mediaMetadata.title.toString() } ==
                    listOf("Alpha", "Bravo", "Alpha")
            }
        } finally {
            controller.stopAndRelease()
        }

        runBlocking { playlists.delete(playlist) }
        compose.waitUntilExactlyOneExists(hasTestTag("custom_tab_missing"), timeoutMillis = 10_000)
        compose.onNode(hasTestTag("custom_tab_rebind")).assertIsEnabled()
        compose.onNode(alpha).assertDoesNotExist()

        val replacement = runBlocking { playlists.create("Replacement playlist") }
        tap("custom_tab_rebind")
        val option = hasText("Replacement playlist")
        compose.waitUntilExactlyOneExists(option, timeoutMillis = 10_000)
        compose.onNode(option).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("custom_tab_empty"), timeoutMillis = 10_000)
        runBlocking {
            val file = Library.database(context).library().files().single { it.mediaStoreId == 1L }
            Library.playlists(context).addFiles(replacement, listOf(file.id))
        }
        compose.waitUntilExactlyOneExists(alpha, timeoutMillis = 10_000)
        compose.onNode(tab("Repeats")).performTouchInput { longClick() }
        tap("custom_tab_edit")
        compose.waitUntilExactlyOneExists(hasText("Replacement playlist"), timeoutMillis = 5_000)
        compose.onNode(hasAnyAncestor(isDialog()) and hasText(compose.activity.getString(R.string.dialog_cancel))).performClick()

        runBlocking { playlists.delete(replacement) }
        val missing = hasTestTag("custom_tab_missing")
        compose.waitUntilExactlyOneExists(missing, timeoutMillis = 10_000)
        val stale = hasText(oldName, substring = true)
        val unavailable = hasText(compose.activity.getString(R.string.custom_tab_missing_playlist))
        val content = hasAnyAncestor(missing)
        compose.waitUntilExactlyOneExists(content and (stale or unavailable), timeoutMillis = 5_000)
        val contentNamesOldPlaylist = compose.onAllNodes(content and stale).fetchSemanticsNodes().isNotEmpty()
        compose.onNode(tab("Repeats")).performTouchInput { longClick() }
        tap("custom_tab_edit")
        val editor = hasAnyAncestor(isDialog())
        compose.waitUntilExactlyOneExists(editor and (stale or unavailable), timeoutMillis = 5_000)
        val editorNamesOldPlaylist = compose.onAllNodes(editor and stale).fetchSemanticsNodes().isNotEmpty()
        assertThat(contentNamesOldPlaylist to editorNamesOldPlaylist)
            .describedAs("neither the tab nor its editor names the original playlist after the replacement disappears")
            .isEqualTo(false to false)
    }

    @Test
    fun aReorderedStockStripStillStartsOnSongs() = withStockPlayerTheme(context, "library_", "custom_library_tab_") {
        val albums = compose.activity.getString(R.string.tab_albums)
        val songs = compose.activity.getString(R.string.tab_songs)
        runBlocking { LibraryTabsSettings(context).move("albums", 0) }
        compose.activityRule.scenario.recreate()

        compose.waitUntil(10_000) {
            val albumNodes = compose.onAllNodes(tab(albums)).fetchSemanticsNodes()
            val songNodes = compose.onAllNodes(tab(songs)).fetchSemanticsNodes()
            albumNodes.size == 1 && songNodes.size == 1 &&
                albumNodes[0].boundsInRoot.left < songNodes[0].boundsInRoot.left
        }
        compose.onNode(tab(songs)).assertIsSelected()
    }

    @Test
    fun aComposerRuleRefreshesWhenTheMergedTagChanges() = withStockPlayerTheme(context, "library_", "custom_library_tab_") {
        val key = "%064x".format(1)
        val identity = runBlocking {
            Library.sync(context).awaitIdle()
            val dao = Library.database(context).library()
            val alpha = dao.files().single { it.mediaStoreId == 1L }
            dao.setContent(alpha.id, key, 60_000, DurationSource.HEADER)
            Library.identities(context).attach()
            checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key)))
        }
        openCreate()
        tap("custom_tab_source_rules")
        compose.onNode(hasTestTag("custom_tab_name")).performTextInput("By composer")
        tap("custom_tab_rule_field_0")
        tap("custom_tab_rule_composer_0")
        compose.onNode(hasTestTag("custom_tab_rule_value_0")).performTextInput("Béla Bartók")
        tap("custom_tab_save")
        compose.waitUntilExactlyOneExists(hasTestTag("custom_tab_empty"), timeoutMillis = 10_000)

        runBlocking {
            val values = ManualTagValues.encode(mapOf("COMPOSER" to listOf("Traditional", "BÉLA BARTÓK")))
            checkNotNull(Library.decisions(context).edit(identity, mapOf(DecidedField.MANUAL_TAGS to values)))
        }
        val alpha = hasAnyAncestor(hasTestTag("custom_tab_songs")) and hasText("Alpha")
        compose.waitUntilExactlyOneExists(alpha, timeoutMillis = 10_000)
        runBlocking {
            val values = ManualTagValues.encode(mapOf("COMPOSER" to emptyList()))
            checkNotNull(Library.decisions(context).edit(identity, mapOf(DecidedField.MANUAL_TAGS to values)))
        }
        compose.waitUntilExactlyOneExists(hasTestTag("custom_tab_empty"), timeoutMillis = 10_000)
        compose.onNode(alpha).assertDoesNotExist()
    }

    @Test
    fun aFolderTabUsesTheExactVolumeAndPath() = withStockPlayerTheme(context, "library_", "custom_library_tab_") {
        openCreate()
        tap("custom_tab_source_folder")
        compose.onNode(hasTestTag("custom_tab_name")).performTextInput("Tree")
        tap("custom_tab_select_source")
        tap("custom_tab_folder_external_primary/Music/Tree/")
        tap("custom_tab_save")

        val songs = hasAnyAncestor(hasTestTag("custom_tab_songs"))
        compose.waitUntilExactlyOneExists(songs and hasText("Alpha"), timeoutMillis = 10_000)
        compose.onNode(songs and hasText("Charlie")).assertDoesNotExist()
        compose.onNode(songs and hasText("Echo")).assertDoesNotExist()
    }

    @Test
    fun anOlderAbsoluteCanvasKeepsItsTabInPlaceAndMakesNewTabsReachable() =
        withStockPlayerTheme(context, "library_", "custom_library_tab_", "ui_canvas_") {
            val canvas = UiCanvasLayout(listOf(UiCanvasItem("tab:songs", UiCanvasBounds(180, 85, 360, 260))))
            runBlocking { check(UiCanvasSettings(context).save("system", UiSurface.LIBRARY, canvas)) }
            val songsTab = hasTestTag("ui_component_tab:songs")
            compose.waitUntilExactlyOneExists(songsTab, timeoutMillis = 10_000)
            val bounds = compose.onNode(songsTab).fetchSemanticsNode().boundsInRoot

            openCreate()
            tap("custom_tab_source_rules")
            compose.onNode(hasTestTag("custom_tab_name")).performTextInput("On the old canvas")
            compose.onNode(hasTestTag("custom_tab_rule_value_0")).performTextInput("Alpha")
            tap("custom_tab_save")

            val newTab = hasAnyAncestor(hasTestTag(LibraryTags.TABS)) and hasText("On the old canvas")
            compose.waitUntilExactlyOneExists(newTab, timeoutMillis = 10_000)
            compose.onNode(newTab).assertIsSelected()
            assertThat(compose.onNode(songsTab).fetchSemanticsNode().boundsInRoot).isEqualTo(bounds)
        }

    @Test
    fun hidingTheOnlyPlacedTabMakesAStockTabReachableAndTheCustomTabCanReturn() =
        withStockPlayerTheme(context, "library_", "custom_library_tab_", "ui_canvas_") {
            val name = "Only placed tab"
            createTitleTab(name)
            val definition = runBlocking { CustomTabsSettings(context).tabs.first().single().definition }
            val canvas = UiCanvasLayout(listOf(UiCanvasItem(definition.componentId, UiCanvasBounds(100, 100, 400, 280))))
            runBlocking { check(UiCanvasSettings(context).save("system", UiSurface.LIBRARY, canvas)) }
            val placedTab = hasTestTag("ui_component_${definition.componentId}")
            compose.waitUntilExactlyOneExists(placedTab, timeoutMillis = 10_000)
            openManager()
            val show = hasContentDescription(compose.activity.getString(R.string.library_tabs_show, name))
            editorList().performScrollToNode(show)
            compose.onNode(show).performClick()
            closeManager()

            val songs = tab(compose.activity.getString(R.string.tab_songs))
            compose.waitUntilExactlyOneExists(songs, timeoutMillis = 10_000)
            compose.onNode(songs).assertIsSelected()
            compose.onNode(placedTab).assertDoesNotExist()

            val target = UiGestureTarget.component("tab:songs")
            val tap = UiGestureSlot(target, UiGesture.builtIn(UiBuiltInGesture.TAP))
            val swipe = UiGestureSlot(target, UiGesture.builtIn(UiBuiltInGesture.SWIPE_LEFT))
            val click = UiGestureAction(UiGestureActionId.STOCK_CLICK, targetId = "tab:songs")
            val gestures = UiGestureProfile().editSurface(UiSurface.LIBRARY, UiSurfaceGestures(
                bindings = listOf(UiGestureBinding(swipe, click)), unbound = setOf(tap),
            ))
            val environment = UiGestureEnvironment(setOf("tab:songs"), emptyList(), listOf(UiGestureBinding(tap, click)))
            runBlocking { check(UiCanvasSettings(context).saveGestures("system", UiSurface.LIBRARY, canvas, gestures, environment)) }
            compose.waitForIdle()

            openManager()
            editorList().performScrollToNode(show)
            compose.onNode(show).performClick()
            closeManager()
            compose.waitUntilExactlyOneExists(placedTab, timeoutMillis = 10_000)
            compose.onNode(songs).assertDoesNotExist()
            assertThat(runBlocking { UiCanvasSettings(context).state.first().layouts.gestures }).isEqualTo(gestures)
        }

    @Test
    fun aPlaylistTabRetainsItsSortWhenSwitchingTabsAndRecreating() =
        withStockPlayerTheme(context, "library_", "custom_library_tab_") {
            val name = "Sorted playlist"
            makePlaylist("Songs in order", listOf(1L, 2L))
            openCreate()
            compose.onNode(hasTestTag("custom_tab_name")).performTextInput(name)
            tap("custom_tab_select_source")
            val playlistOption = hasText("Songs in order")
            compose.waitUntilExactlyOneExists(playlistOption, timeoutMillis = 10_000)
            compose.onNode(playlistOption).performClick()
            tap("custom_tab_save")

            val rows = hasAnyAncestor(hasTestTag("custom_tab_songs"))
            compose.waitUntilExactlyOneExists(rows and hasText("Alpha"), timeoutMillis = 10_000)
            val sort = hasContentDescription(compose.activity.getString(R.string.library_sort))
            compose.onNode(sort).performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.sort_reverse))).performClick()
            fun reversed() = compose.waitUntil(10_000) {
                val alphas = compose.onAllNodes(rows and hasText("Alpha")).fetchSemanticsNodes()
                val bravos = compose.onAllNodes(rows and hasText("Bravo")).fetchSemanticsNodes()
                alphas.size == 1 && bravos.size == 1 && bravos[0].boundsInRoot.top < alphas[0].boundsInRoot.top
            }
            reversed()

            compose.onNode(tab(compose.activity.getString(R.string.tab_songs))).performScrollTo().performClick()
            compose.waitUntilDoesNotExist(hasTestTag("custom_tab_songs"), timeoutMillis = 5_000)
            compose.onNode(tab(name)).performScrollTo().performClick()
            reversed()
            compose.activityRule.scenario.recreate()
            reversed()
        }

    private fun createTitleTab(name: String) {
        openCreate()
        tap("custom_tab_source_rules")
        compose.onNode(hasTestTag("custom_tab_name")).performTextInput(name)
        compose.onNode(hasTestTag("custom_tab_rule_value_0")).performTextInput("Alpha")
        tap("custom_tab_save")
        compose.waitUntilExactlyOneExists(tab(name), timeoutMillis = 10_000)
    }

    private fun tab(name: String) = hasAnyAncestor(hasTestTag(LibraryTags.TABS)) and hasText(name)

    private fun editorList() = compose.onNode(hasAnyAncestor(hasTestTag("library_tab_editor")) and hasScrollAction())

    private fun makePlaylist(name: String, mediaIds: List<Long>): Long = runBlocking {
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        val files = dao.files().associateBy { it.mediaStoreId }
        for (id in mediaIds.distinct()) {
            dao.setContent(files.getValue(id).id, "%064x".format(id), 60_000, DurationSource.HEADER)
        }
        Library.identities(context).attach()
        val playlist = UserData.playlists(context).create(name)
        Library.playlists(context).addFiles(playlist, mediaIds.map { files.getValue(it).id })
        playlist
    }

    private fun openCreate() {
        openManager()
        tap("custom_tab_create")
    }

    private fun openManager() {
        val manage = hasContentDescription(compose.activity.getString(R.string.library_tabs_manage))
        compose.waitUntilExactlyOneExists(manage, timeoutMillis = 10_000)
        compose.onNode(manage).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("library_tab_editor"), timeoutMillis = 5_000)
    }

    private fun closeManager() {
        val close = hasContentDescription(compose.activity.getString(R.string.library_tabs_close))
        compose.onNode(close).performClick()
        compose.waitUntilDoesNotExist(hasTestTag("library_tab_editor"), timeoutMillis = 5_000)
    }

    private fun tap(tag: String) {
        val matcher = hasTestTag(tag)
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 5_000)
        compose.onNode(matcher).performClick()
    }
}
