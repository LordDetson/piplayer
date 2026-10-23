package io.github.lorddetson.piplayer.ui.songs

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The song list screen: the permission cards, the list, and a tap (docs/playback.md §2.1, §2.2). */
@RunWith(AndroidJUnit4::class)
class SongsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val songs = listOf(
        Song(1, "file:///1.mp3", "Ёжик в тумане", "Сказки", null, 125_000),
        Song(2, "file:///2.mp3", "Halo", null, null, null),
    )

    @Test
    fun withoutThePermissionAButtonAsksForIt() {
        var asked = false
        compose.setContent {
            SongsContent(SongsState.NeedsPermission(deniedForGood = false), { asked = true }, {}, { _, _ -> })
        }

        compose.onNodeWithText(text(R.string.songs_permission_explanation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.songs_allow_access)).performClick()

        assertThat(asked).isTrue()
    }

    @Test
    fun deniedForGoodOffersTheSettings() {
        var settings = false
        var asked = false
        compose.setContent {
            SongsContent(SongsState.NeedsPermission(deniedForGood = true), { asked = true }, { settings = true }, { _, _ -> })
        }

        compose.onNodeWithText(text(R.string.songs_denied_explanation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.songs_open_settings)).performClick()
        compose.onNodeWithText(text(R.string.songs_try_again)).performClick()

        assertThat(settings).isTrue()
        assertThat(asked).isTrue()
    }

    @Test
    fun anEmptyListSaysSo() {
        compose.setContent { SongsContent(SongsState.Loaded(emptyList()), {}, {}, { _, _ -> }) }

        compose.onNodeWithText(text(R.string.songs_empty)).assertIsDisplayed()
    }

    @Test
    fun rowsShowTitleArtistAndLength() {
        compose.setContent { SongsContent(SongsState.Loaded(songs), {}, {}, { _, _ -> }) }

        compose.onNodeWithText("Ёжик в тумане").assertIsDisplayed()
        compose.onNodeWithText("Сказки · 2:05").assertIsDisplayed()
        compose.onNodeWithText("Halo").assertIsDisplayed()
        compose.onNodeWithText("${text(R.string.songs_unknown_artist)} · --:--").assertIsDisplayed()
    }

    @Test
    fun aTapReportsTheRowAndTheSong() {
        var tapped: Pair<Int, Song>? = null
        compose.setContent { SongsContent(SongsState.Loaded(songs), {}, {}, { row, song -> tapped = row to song }) }

        compose.onNodeWithText("Halo").performClick()

        assertThat(tapped).isEqualTo(1 to songs[1])
    }

    @Test
    fun theLastOf20000SongsCanBeReached() {
        val many = List(20_000) { Song(it + 1L, "file:///$it.mp3", "Song %05d".format(it + 1), null, null, null) }
        compose.setContent { SongsContent(SongsState.Loaded(many), {}, {}, { _, _ -> }) }

        compose.onNodeWithTag(SongsTags.LIST).performScrollToIndex(19_999)

        compose.onNodeWithText("Song 20000").assertIsDisplayed()
    }
}

/** The whole screen with the permission granted: the list of the song source, and a tap that plays. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SongsScreenTest {

    private val songs = SongSourceRule {
        listOf(2L to "Bravo", 1L to "Alpha", 3L to "Charlie")
            .map { (id, title) -> SongSourceRule.song(id, title, PlaybackTesting.LONG_FIXTURE) }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs).around(compose)

    @Test
    fun theListShowsTheSongsInTitleOrderAndATapPlays() {
        // A restored mini player may show the same title as a row (docs/playback.md §4.4).
        val inList = hasAnyAncestor(hasTestTag(SongsTags.LIST))
        // The list is read off the main thread, which Compose does not wait for.
        compose.waitUntilExactlyOneExists(hasTestTag(SongsTags.LIST), timeoutMillis = 10_000)
        compose.onNodeWithTag(SongsTags.LIST).performScrollToNode(hasText("Charlie"))
        val tops = listOf("Alpha", "Bravo", "Charlie").map {
            compose.onNode(hasText(it) and inList).getBoundsInRoot().top
        }
        assertThat(tops).isSorted()

        compose.onNode(hasText("Bravo") and inList).performClick()

        val controller = connectController()
        try {
            controller.waitUntil("Bravo, the second row, plays") {
                isPlaying && currentMediaItem?.mediaId == "2" && currentMediaItemIndex == 1 && mediaItemCount == 3
            }
        } finally {
            controller.stopAndRelease()
        }
    }

    @Test
    fun theListFollowsChangesOfTheMediaIndex() {
        compose.waitUntilExactlyOneExists(hasAnyAncestor(hasTestTag(SongsTags.LIST)) and hasText("Charlie"), timeoutMillis = 10_000)
        songs.songs = songs.songs + SongSourceRule.song(4, "Delta")

        // Any change of the media index makes the screen read the list again: here, a file of the app's own.
        val resolver = TestFiles.targetContext.contentResolver
        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "change.mp3")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/PiPlayerTest/")
            },
        )
        try {
            compose.onNodeWithTag(SongsTags.LIST).performScrollToIndex(0)
            compose.waitUntilExactlyOneExists(
                hasAnyAncestor(hasTestTag(SongsTags.LIST)) and hasText("Delta"),
                timeoutMillis = 10_000,
            )
        } finally {
            uri?.let { resolver.delete(it, null, null) }
        }
    }
}
