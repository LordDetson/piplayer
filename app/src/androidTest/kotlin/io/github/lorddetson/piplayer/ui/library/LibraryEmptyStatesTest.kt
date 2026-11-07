package io.github.lorddetson.piplayer.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The tabs of a library without music (docs/library.md §14.1). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryEmptyStatesTest {

    private val index = FakeMediaIndex().apply { volume().rows = emptyList() }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(compose)

    private fun text(id: Int) = compose.activity.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)

    private fun tab(id: Int) {
        val matcher = hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS))
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    @Test
    fun everyTabOfALibraryWithoutMusicSaysSo() {
        val noMusic = hasText(text(R.string.library_no_music))
        waitFor(noMusic)
        for (id in listOf(R.string.tab_albums, R.string.tab_artists, R.string.tab_folders, R.string.tab_songs)) {
            tab(id)
            waitFor(noMusic)
        }
    }

    @Test
    fun itsButtonOpensTheSettings() {
        val button = hasText(text(R.string.library_no_music_settings))
        waitFor(button)

        compose.onNode(button).performClick()

        waitFor(hasText(text(R.string.settings_title)))
        waitFor(hasContentDescription(text(R.string.settings_back)))
    }
}
