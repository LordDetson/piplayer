package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.tagpass.PassProgress
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.tagpass.TagPassState
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Songs screen while the tag pass lands its batches on the real library database (docs/library.md §1.4, §5.4). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SongsInPlaceScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = List(300) { i ->
            val id = i + 1L
            row(id, title = "Song %03d".format(id), name = "$id.mp3")
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    @After
    fun clean() {
        TagPassState.progress.value = PassProgress()
        SongList.passFinished()
    }

    private fun listShown() {
        compose.waitUntilExactlyOneExists(hasTestTag(SongsTags.LIST), timeoutMillis = 15_000)
        compose.waitUntil(15_000) { SongList.current()?.size == 300 }
    }

    /** A pass that gives each song the title [title] makes of its index ID. */
    private fun landAPass(finish: Boolean = true, title: (Long) -> String) = runBlocking {
        val sync = Library.sync(context)
        SongList.passStarted()
        val reader = io.github.lorddetson.piplayer.library.tagpass.PassReader { file ->
            PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf(title(file.mediaStoreId)))))
        }
        TagPass(Library.database(context), sync, reader, cyrillicLanguage = { false }, onBatch = SongList::applyInPlace).run()
        if (finish) SongList.passFinished()
    }

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun goToBackgroundAndBack() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun aBatchChangesTheVisibleRowsAndTheListStaysWhereItWas() {
        listShown()
        compose.onNodeWithTag(SongsTags.LIST).performScrollToIndex(150)
        compose.waitForIdle()
        val before = top("Song 151")

        // Backwards: sorted by the new titles, row 151 would move far from where it is.
        landAPass(finish = false) { "Tag %03d".format(400 - it) }
        compose.waitUntilExactlyOneExists(hasText("Tag 249"), timeoutMillis = 10_000)

        assertThat(top("Tag 249")).isEqualTo(before)
        assertThat(compose.onAllNodes(hasText("Song 151")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aTapPlaysTheOrderTheScreenShows() {
        listShown()

        landAPass(finish = false) { "Tag %03d".format(400 - it) }

        val list = SongList.current()!!
        assertThat(list.first().id).isEqualTo(1L)
        assertThat(list.first().title).isEqualTo("Tag 399")
    }

    @Test
    fun aReturnFromTheBackgroundDuringThePassKeepsTheOrder() {
        listShown()
        landAPass(finish = false) { "Tag %03d".format(400 - it) }
        compose.waitUntilExactlyOneExists(hasText("Tag 399"), timeoutMillis = 10_000)

        goToBackgroundAndBack()
        compose.waitForIdle()

        compose.onNodeWithText("Tag 399").assertExists()
        assertThat(SongList.current()!!.first().id).isEqualTo(1L)
    }

    @Test
    fun theFirstReturnAfterThePassSortsOnceAndStaysOnTheSameSong() {
        listShown()
        compose.onNodeWithTag(SongsTags.LIST).performScrollToIndex(150)
        compose.waitForIdle()
        landAPass(finish = true) { "Tag %03d".format(400 - it) }
        compose.waitUntilExactlyOneExists(hasText("Tag 249"), timeoutMillis = 10_000)

        goToBackgroundAndBack()

        compose.waitUntil(10_000) { SongList.current()?.first()?.id == 300L }
        compose.waitUntilExactlyOneExists(hasText("Tag 249"), timeoutMillis = 10_000)
        val sorted = SongList.current()

        goToBackgroundAndBack()
        compose.waitForIdle()

        assertThat(SongList.current()).isSameAs(sorted)
    }

    @Test
    fun withoutAPassAReturnFromTheBackgroundReadsNothing() {
        listShown()
        val list = SongList.current()

        goToBackgroundAndBack()
        compose.waitForIdle()

        assertThat(SongList.current()).isSameAs(list)
    }

    @Test
    fun aNewFileDuringThePassKeepsTheFirstVisibleRow() = runBlocking<Unit> {
        listShown()
        compose.onNodeWithTag(SongsTags.LIST).performScrollToIndex(150)
        compose.waitForIdle()
        landAPass(finish = false) { "Tag %03d".format(400 - it) }
        compose.waitUntilExactlyOneExists(hasText("Tag 249"), timeoutMillis = 10_000)
        val before = top("Tag 249")

        index.volume().rows = index.volume().rows + row(301, title = "Tag 399 and a bit", name = "301.mp3")
        Library.sync(context).sync()

        compose.waitUntil(10_000) { SongList.current()?.size == 301 }
        compose.waitForIdle()
        assertThat(top("Tag 249")).isEqualTo(before)
    }

    @Test
    fun theProgressLineShowsForALongPassAndLeavesTheListWhereItIs() {
        listShown()
        compose.waitForIdle()
        val before = top("Song 001")

        TagPassState.progress.value = PassProgress(running = true, done = 10, total = 49)
        compose.waitForIdle()
        assertThat(compose.onAllNodes(hasTestTag(SongsTags.PROGRESS)).fetchSemanticsNodes()).isEmpty()

        TagPassState.progress.value = PassProgress(running = true, done = 10, total = 50)
        compose.waitUntilExactlyOneExists(hasTestTag(SongsTags.PROGRESS), timeoutMillis = 5_000)
        assertThat(top("Song 001")).isEqualTo(before)

        TagPassState.progress.value = PassProgress(running = false, done = 50, total = 50)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SongsTags.PROGRESS)).fetchSemanticsNodes().isEmpty() }
    }
}
