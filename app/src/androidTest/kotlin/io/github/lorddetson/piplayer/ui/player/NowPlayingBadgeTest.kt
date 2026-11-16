package io.github.lorddetson.piplayer.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.provider.ProviderId
import io.github.lorddetson.piplayer.library.details.Badge
import io.github.lorddetson.piplayer.library.details.BadgeState
import io.github.lorddetson.piplayer.ui.details.BadgeHost
import io.github.lorddetson.piplayer.ui.details.BadgeTags
import io.github.lorddetson.piplayer.ui.details.LocalBadgeHost
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import io.github.lorddetson.piplayer.ui.player.TestPlayer.TestSong
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.text.NumberFormat
import java.util.Collections
import kotlinx.coroutines.flow.flowOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The metadata badge on Now Playing (TI §6, docs/library.md §18.7). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NowPlayingBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val identified = Collections.synchronizedList(mutableListOf<IdentifyTarget>())
    private val details = Collections.synchronizedList(mutableListOf<Long>())
    private val asked = Collections.synchronizedList(mutableListOf<Long>())

    /** Now Playing on a test player whose current item is the library song of file 0, with [badge] for it. */
    private fun show(badge: Badge?, host: Boolean = true) {
        val player = compose.runOnUiThread { TestPlayer(listOf(TestSong("Halo", "Beyoncé", "I Am... Sasha Fierce"))) }
        val badges = BadgeHost(badges = { fileId -> asked += fileId; flowOf(badge) }, showDetails = { details += it })
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides { identified += it }, LocalBadgeHost provides badges.takeIf { host }) {
                    NowPlaying(player, null, onClose = {})
                }
            }
        }
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun anIdentifiedSongShowsItsSourceAndConfidenceAndOpensItsDetails() {
        show(Badge(BadgeState.IDENTIFIED, ProviderId.MUSICBRAINZ, 0.97))
        val percent = NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).format(0.97)

        compose.waitUntilAtLeastOneExists(hasTestTag(BadgeTags.BADGE), 5_000)
        compose.onNodeWithTag(BadgeTags.BADGE)
            .assertContentDescriptionEquals(text(R.string.badge_description, text(R.string.badge_identified, "MusicBrainz", percent)))
            .assert(hasClickLabel(text(R.string.badge_show_identification)))
            .performClick()

        compose.waitUntil(5_000) { details.isNotEmpty() }
        assertThat(details).containsExactly(0L)
        assertThat(asked).contains(0L)
        assertThat(identified).isEmpty()
    }

    @Test
    fun anEditedSongOpensItsDetailsToo() {
        show(Badge(BadgeState.EDITED))

        compose.waitUntilAtLeastOneExists(hasTestTag(BadgeTags.BADGE), 5_000)
        compose.onNodeWithTag(BadgeTags.BADGE)
            .assertContentDescriptionEquals(text(R.string.badge_description, text(R.string.badge_edited)))
            .performClick()

        compose.waitUntil(5_000) { details.isNotEmpty() }
        assertThat(details).containsExactly(0L)
    }

    @Test
    fun aSongFromItsTagsOpensTheReview() {
        show(Badge(BadgeState.FROM_TAGS))

        compose.waitUntilAtLeastOneExists(hasTestTag(BadgeTags.BADGE), 5_000)
        compose.onNodeWithTag(BadgeTags.BADGE)
            .assertContentDescriptionEquals(text(R.string.badge_description, text(R.string.badge_from_tags)))
            .assert(hasClickLabel(text(R.string.identify)))
            .performClick()

        compose.waitUntil(5_000) { identified.isNotEmpty() }
        assertThat(identified).containsExactly(IdentifyTarget.Song(0))
        assertThat(details).isEmpty()
    }

    @Test
    fun noBadgeWithoutOne() {
        show(null)
        compose.waitUntil(5_000) { asked.isNotEmpty() }
        compose.waitForIdle()

        assertThat(compose.onAllNodes(hasTestTag(BadgeTags.BADGE)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun noBadgeWhereTheAppProvidesNone() {
        show(Badge(BadgeState.FROM_TAGS), host = false)
        compose.waitForIdle()

        assertThat(compose.onAllNodes(hasTestTag(BadgeTags.BADGE)).fetchSemanticsNodes()).isEmpty()
    }
}
