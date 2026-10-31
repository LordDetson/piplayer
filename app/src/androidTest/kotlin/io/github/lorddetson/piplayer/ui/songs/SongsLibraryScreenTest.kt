package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Songs screen over the library database during a first launch (docs/library.md §1.4). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SongsLibraryScreenTest {

    private val gate = CountDownLatch(1)
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, "Alpha"), row(2, "Bravo"))
        this.gate = this@SongsLibraryScreenTest.gate
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    @After
    fun openTheGate() = gate.countDown()

    @Test
    fun theFirstSyncShowsLoadingNotAnEmptyLibrary() {
        compose.waitUntilExactlyOneExists(hasTestTag(SongsTags.LOADING), timeoutMillis = 10_000)
        val empty = TestFiles.targetContext.getString(R.string.songs_empty)
        repeat(15) {
            compose.waitForIdle()
            check(compose.onAllNodes(hasText(empty)).fetchSemanticsNodes().isEmpty()) {
                "The library showed as empty during its first sync"
            }
            Thread.sleep(100)
        }

        gate.countDown()

        compose.waitUntilExactlyOneExists(hasAnyAncestor(hasTestTag(SongsTags.LIST)) and hasText("Bravo"), timeoutMillis = 10_000)
    }
}
