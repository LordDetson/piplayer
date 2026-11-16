package io.github.lorddetson.piplayer.ui.identification

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.player.NowPlaying
import io.github.lorddetson.piplayer.ui.player.TestPlayer
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuItems
import io.github.lorddetson.piplayer.ui.songs.TrackMenuPlace
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The entry points of the review (TI §1; §11.2 M3 "Review screen A"): the track menu, Now Playing and track details. */
@RunWith(AndroidJUnit4::class)
class IdentifyEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val opened = mutableListOf<IdentifyTarget>()
    private val identify: (IdentifyTarget) -> Unit = { opened += it }

    private val song = Song(5, "file:///5.mp3", "Track", "Artist", "Album", 60_000)
    private val menu = SongMenu(
        links = { null },
        openAlbum = {},
        openArtist = {},
        openFolder = { _, _ -> },
        details = {},
        openedDetails = {},
    )

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun theTrackMenuHasIdentifyRightBeforeDetailsAndOpensTheSongsReview() {
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides identify) {
                    Column { TrackMenuItems(song, TrackMenuPlace.LIST, menu, close = {}) }
                }
            }
        }

        val labels = compose.onAllNodes(hasText(text(R.string.identify)).or(hasText(text(R.string.details))))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertThat(labels).containsExactly(text(R.string.identify), text(R.string.details))
        compose.onAllNodesWithText(text(R.string.identify))[0].performClick()

        assertThat(opened).containsExactly(IdentifyTarget.Song(5))
    }

    @Test
    fun anOpenedFilesMenuHasIdentifyAndDetails() {
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides identify) {
                    Column { TrackMenuItems(null, TrackMenuPlace.NOW_PLAYING, menu, close = {}, openedUri = "content://downloads/a.mp3") }
                }
            }
        }

        compose.onAllNodesWithText(text(R.string.details))[0].assertHasClickAction()
        compose.onAllNodesWithText(text(R.string.identify))[0].performClick()

        assertThat(opened).containsExactly(IdentifyTarget.Opened("content://downloads/a.mp3"))
    }

    @Test
    fun withoutAWayToOpenTheReviewTheMenuHasNoIdentify() {
        compose.setContent {
            PiPlayerTheme { Column { TrackMenuItems(song, TrackMenuPlace.LIST, menu, close = {}) } }
        }

        assertThat(compose.onAllNodesWithText(text(R.string.identify)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun nowPlayingHasAnIdentifyButtonForTheCurrentSong() {
        val player = compose.runOnUiThread { TestPlayer(listOf(TestPlayer.TestSong("Halo", "Beyoncé"))) }
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides identify) { NowPlaying(player, null, onClose = {}) }
            }
        }

        compose.onNode(hasContentDescription(text(R.string.identify)) and isButton()).performClick()

        // The test player's first item has the media ID "0", a library song's file ID.
        assertThat(opened).containsExactly(IdentifyTarget.Song(0))
    }

    @Test
    fun theDetailsActionOpensTheReviewOfItsSongOrFile() {
        compose.setContent {
            PiPlayerTheme {
                CompositionLocalProvider(LocalIdentify provides identify) {
                    Column {
                        IdentifyAction(fileId = 9, uri = null)
                        IdentifyAction(fileId = null, uri = "content://downloads/b.mp3")
                    }
                }
            }
        }

        val buttons = compose.onAllNodes(hasContentDescription(text(R.string.identify)) and isButton())
        buttons[0].performClick()
        buttons[1].performClick()

        assertThat(opened).containsExactly(IdentifyTarget.Song(9), IdentifyTarget.Opened("content://downloads/b.mp3"))
    }

    @Test
    fun withoutAWayToOpenTheReviewThereIsNoButton() {
        compose.setContent { PiPlayerTheme { IdentifyAction(fileId = 9, uri = null) } }

        assertThat(compose.onAllNodes(hasContentDescription(text(R.string.identify))).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithContentDescription(text(R.string.identify)).assertDoesNotExist()
    }
}
