package io.github.lorddetson.piplayer.ui.details

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.details.DetailField
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.ui.player.QueueTags
import io.github.lorddetson.piplayer.ui.playlists.PlaylistTags
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import io.github.lorddetson.piplayer.userdata.UserData
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** "Details" in every track menu and the details screen (docs/library.md §18, docs/playback.md §9.1). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrackDetailsScreenTest {

    private val context = TestFiles.targetContext
    private val recording = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            // The index's title differs from the tags': details show the merged value, which is the tags' once read.
            row(1, "alpha from the index", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
        )
    }

    /** Alpha is read, as after the tag pass, with a recording ID; Bravo is not. The player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking {
                Library.sync(context).awaitIdle()
                val database = Library.database(context)
                val dao = database.library()
                val alpha = fileId(1)
                val tags = TrackTags.from(
                    mapOf(
                        "TITLE" to listOf("Alpha"),
                        "ARTIST" to listOf("Ann"),
                        "ALBUM" to listOf("Red Album"),
                        "TRACKNUMBER" to listOf("1"),
                        "MUSICBRAINZ_TRACKID" to listOf(recording),
                    ),
                )
                dao.addTrack(TrackEntity.of(alpha, tags))
                dao.markRead(alpha, at = 1, readerVersion = Int.MAX_VALUE, format = "mpeg", codec = "mp3", sampleRate = 44_100,
                    bitDepth = null, channels = 2, bitrate = 320, tagEncoding = null, coverPresent = false, coverBytes = null,
                    tagLengthMs = 60_000)
                dao.setContent(alpha, "%064x".format(1), 60_000, DurationSource.HEADER)
                TrackDisplayWriter(database).refresh(listOf(alpha))
                Library.identities(context).attach()
            }
            SongList.forget()
            val cleaner = connectController()
            onMain {
                cleaner.stop()
                cleaner.clearMediaItems()
            }
            cleaner.waitUntil("the player is empty") { mediaItemCount == 0 }
            runBlocking { PlaybackStateStore.get(context).clear() }
            onMain { cleaner.release() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(setUp).around(compose)

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    private val inDetails = hasAnyAncestor(hasTestTag(DetailsTags.LIST))

    private fun row(field: DetailField) = hasTestTag(DetailsTags.row(field)) and inDetails

    /** Waits for the details screen, scrolls to [field]'s row and checks that it says [value]. */
    private fun assertRow(field: DetailField, value: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(DetailsTags.LIST), timeoutMillis = 10_000)
        compose.onNode(hasTestTag(DetailsTags.LIST)).performScrollToNode(hasTestTag(DetailsTags.row(field)))
        compose.waitUntilAtLeastOneExists(row(field) and hasText(value, substring = true), timeoutMillis = 10_000)
    }

    private fun openDetailsFromMenu(title: String, within: SemanticsMatcher) {
        tap(hasContentDescription(text(R.string.song_menu, title)) and within)
        tap(hasText(text(R.string.details)))
    }

    @Test
    fun theSongMenuOpensTheDetailsWithTheMergedValuesAndTheFile() {
        openDetailsFromMenu("Alpha", hasAnyAncestor(hasTestTag(SongsTags.LIST)))

        assertRow(DetailField.TITLE, "Alpha")
        assertThat(compose.onAllNodes(row(DetailField.TITLE) and hasText("alpha from the index")).fetchSemanticsNodes()).isEmpty()
        assertRow(DetailField.ARTIST, "Ann")
        assertRow(DetailField.CODEC, "mp3")
        assertRow(DetailField.FILE_NAME, "01 Alpha.mp3")
        assertRow(DetailField.RECORDING_ID, recording)

        // Back returns to the library.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilDoesNotExist(hasTestTag(DetailsTags.LIST), timeoutMillis = 10_000)
    }

    @Test
    fun aSongWhoseTagsAreNotReadShowsTheIndexValues() {
        openDetailsFromMenu("Bravo", hasAnyAncestor(hasTestTag(SongsTags.LIST)))

        assertRow(DetailField.TITLE, "Bravo")
        // Not read yet, or failed by now: the tag pass cannot open the files of a test index.
        compose.onNode(hasTestTag(DetailsTags.LIST)).performScrollToNode(hasTestTag(DetailsTags.row(DetailField.TAGS)))
        compose.waitUntilAtLeastOneExists(row(DetailField.TAGS), timeoutMillis = 10_000)
        assertThat(compose.onAllNodes(row(DetailField.CODEC)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aMusicBrainzRowOpensItsPageWithAViewIntent() {
        val seen = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW || intent.data?.host != "musicbrainz.org") return null
                seen.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            openDetailsFromMenu("Alpha", hasAnyAncestor(hasTestTag(SongsTags.LIST)))
            assertRow(DetailField.RECORDING_ID, recording)
            compose.onNode(row(DetailField.RECORDING_ID)).performClick()

            compose.waitUntil(10_000) { seen.get() != null }
            assertThat(seen.get()!!.data).isEqualTo(Uri.parse("https://musicbrainz.org/recording/$recording"))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun aLongPressCopiesTheValue() {
        openDetailsFromMenu("Alpha", hasAnyAncestor(hasTestTag(SongsTags.LIST)))
        assertRow(DetailField.FILE_NAME, "01 Alpha.mp3")

        compose.onNode(row(DetailField.FILE_NAME)).performTouchInput { longClick() }

        compose.waitUntil(10_000) {
            val clip = AtomicReference<String?>()
            compose.activityRule.scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.set(clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            clip.get() == "01 Alpha.mp3"
        }
    }

    @Test
    fun theAlbumPageSongMenuHasDetails() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        openDetailsFromMenu("Bravo", hasAnyAncestor(hasTestTag(LibraryTags.LIST)))

        assertRow(DetailField.TITLE, "Bravo")
    }

    @Test
    fun aPlaylistEntryHasTheWholeTrackMenu() {
        runBlocking {
            val id = UserData.playlists(context).create("Mix")
            Library.playlists(context).addFiles(id, listOf(fileId(1)))
        }
        tab(R.string.tab_playlists)
        tap(hasText("Mix") and hasAnyAncestor(hasTestTag(PlaylistTags.LIST)))
        tap(hasContentDescription(text(R.string.song_menu, "Alpha")) and hasAnyAncestor(hasTestTag(PlaylistTags.PAGE_LIST)))

        for (entry in listOf(R.string.play_next, R.string.add_to_playlist, R.string.go_to_album, R.string.favorite_add, R.string.playlist_remove)) {
            compose.waitUntilAtLeastOneExists(hasText(text(entry)), timeoutMillis = 10_000)
        }
        tap(hasText(text(R.string.details)))

        assertRow(DetailField.TITLE, "Alpha")
    }

    @Test
    fun aQueueRowHasTheTrackMenu() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        // The last song: the files of a test index cannot play, and a broken song is skipped to the next.
        tap(hasText("Bravo") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        tap(hasTestTag(PlayerTags.MINI_PLAYER))
        tap(hasContentDescription(text(R.string.queue_open)))
        tap(hasContentDescription(text(R.string.song_menu, "Bravo")) and hasAnyAncestor(hasTestTag(QueueTags.LIST)))

        for (entry in listOf(R.string.add_to_playlist, R.string.go_to_album, R.string.queue_remove)) {
            compose.waitUntilAtLeastOneExists(hasText(text(entry)), timeoutMillis = 10_000)
        }
        tap(hasText(text(R.string.details)))

        assertRow(DetailField.TITLE, "Bravo")
    }

    @Test
    fun nowPlayingsMoreOptionsHaveTheMenuWithoutTheQueueEntries() {
        tab(R.string.tab_albums)
        tap(hasText("Red Album") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        // The last song: the files of a test index cannot play, and a broken song is skipped to the next.
        tap(hasText("Bravo") and hasAnyAncestor(hasTestTag(LibraryTags.LIST)))
        tap(hasTestTag(PlayerTags.MINI_PLAYER))
        tap(hasTestTag(PlayerTags.MORE) and hasContentDescription(text(R.string.page_menu)))

        compose.waitUntilAtLeastOneExists(hasText(text(R.string.add_to_playlist)), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.go_to_album)), timeoutMillis = 10_000)
        assertThat(compose.onAllNodes(hasText(text(R.string.play_next))).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText(text(R.string.favorite_add))).fetchSemanticsNodes()).isEmpty()
        tap(hasText(text(R.string.details)))

        assertRow(DetailField.TITLE, "Bravo")
        // Back returns to Now Playing, which the details opened over.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 10_000)
    }
}
