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
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The actions of the Library section of the settings (docs/library.md §5.6). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryActionsScreenTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, title = "Index title"))
    }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    private fun dao() = Library.database(context).library()

    private fun openSettings() {
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription(text(R.string.settings_open))).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_refresh_library)), timeoutMillis = 5_000)
    }

    @Test
    fun refreshingFindsANewFile() {
        compose.waitUntil(10_000) { runBlocking { dao().files().size } == 1 }
        openSettings()
        index.volume().rows = listOf(row(1, title = "Index title"), row(2, title = "New file"))

        compose.onNodeWithText(text(R.string.settings_refresh_library)).performScrollTo().performClick()

        compose.waitUntil(10_000) { runBlocking { dao().files().size } == 2 }
    }

    @Test
    fun readingAllTagsAgainLeavesTheListAsItIsAndAsksTheNextPassToReadThem() = runBlocking<Unit> {
        compose.waitUntil(10_000) { runBlocking { dao().files().size } == 1 }
        val id = dao().files().single().id
        dao().addTrack(TrackEntity.of(id, TrackTags(title = "Tag title")))
        dao().markRead(id, 1_000, 1, "mpeg", "mp3", null, null, null, null, null, false, null, null)
        TrackDisplayWriter(Library.database(context)).refresh(listOf(id))
        openSettings()

        compose.onNodeWithText(text(R.string.settings_reread_tags)).performScrollTo().performClick()

        compose.waitUntil(10_000) { runBlocking { dao().files().single().readerVersion } == 0 }
        with(dao().files().single()) {
            assertThat(tagPassAt).isEqualTo(1_000)
        }
        assertThat(dao().displays().single().title).isEqualTo("Tag title")
    }
}
