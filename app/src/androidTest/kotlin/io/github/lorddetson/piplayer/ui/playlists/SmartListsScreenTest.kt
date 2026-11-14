package io.github.lorddetson.piplayer.ui.playlists

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.MissingReasons
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.awaitState
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The smart lists on the Playlists tab and their pages (docs/library.md §17.2). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmartListsScreenTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/Ann/", name = "01 Alpha.mp3", artist = "Ann", album = "Red"),
            row(2, "Bravo", path = "Music/Ann/", name = "02 Bravo.mp3", artist = "Ann", album = "Red"),
            row(3, "Charlie", path = "Music/Bob/", name = "03 Charlie.mp3", artist = "Bob", album = "Blue"),
        )
    }

    /** Every song has an identity, as after the tag pass; the player starts empty. */
    private val setUp = object : ExternalResource() {
        override fun before() {
            runBlocking {
                Library.sync(context).awaitIdle()
                val dao = Library.database(context).library()
                for (id in 1L..3L) dao.setContent(fileId(id), key(id), 60_000, DurationSource.HEADER)
                Library.identities(context).attach()
            }
            val cleaner = connectController()
            onMain {
                cleaner.stop()
                cleaner.clearMediaItems()
                cleaner.shuffleModeEnabled = false
            }
            cleaner.waitUntil("the player is empty") { mediaItemCount == 0 }
            runBlocking { PlaybackStateStore.get(context).clear() }
            onMain { cleaner.release() }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(setUp).around(compose)

    private fun key(n: Long) = "%064x".format(n)

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun identity(n: Long) = runBlocking { checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(n)))) }

    private fun favorite(n: Long) = runBlocking { UserData.favorites(context).set(identity(n), favorite = true) }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun songs(count: Int) = compose.activity.resources.getQuantityString(R.plurals.songs_count, count, count)

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntilAtLeastOneExists(matcher, timeoutMillis = 10_000)
        compose.onAllNodes(matcher)[0].performClick()
    }

    private fun tab(id: Int) = tap(hasText(text(id)) and hasAnyAncestor(hasTestTag(LibraryTags.TABS)))

    /** The row of the smart list [name] on the Playlists tab, showing [count] songs. */
    private fun smartRow(name: Int, count: Int) =
        hasTestTag(SmartListTags.ROW) and hasText(text(name)) and hasText(songs(count), substring = true)

    private fun onPage(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(SmartListTags.PAGE_LIST))

    @Test
    fun theSmartListsLeadThePlaylistsTabWithTheirNumbersWhichFollowTheLibrary() {
        tab(R.string.tab_playlists)

        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_favorites, 0), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_recently_added, 3), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_recently_played, 0), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_most_played, 0), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_missing_info, 0), timeoutMillis = 10_000)
        // Above the user's playlists, of which there are none.
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.playlists_none)), timeoutMillis = 10_000)

        // A new favorite reaches the number through the mirror, while the tab is open.
        favorite(2)
        compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_favorites, 1), timeoutMillis = 10_000)
    }

    @Test
    fun aSmartListPagePlaysFromARowAndWithPlayAndShuffle() {
        favorite(2)
        favorite(3)
        tab(R.string.tab_playlists)
        tap(smartRow(R.string.smart_favorites, 2))

        tap(onPage(hasText("Charlie")))
        val controller = connectController()
        try {
            controller.awaitState("Charlie plays from Favorites") {
                mediaItemCount == 2 && currentMediaItem?.mediaMetadata?.title?.toString() == "Charlie"
            }
            tap(hasTestTag(SmartListTags.PLAY))
            controller.awaitState("Favorites play from Bravo") {
                mediaItemCount == 2 && currentMediaItemIndex == 0 && currentMediaItem?.mediaMetadata?.title?.toString() == "Bravo" &&
                    !shuffleModeEnabled
            }
            tap(hasTestTag(SmartListTags.SHUFFLE))
            controller.awaitState("Favorites play shuffled") { mediaItemCount == 2 && shuffleModeEnabled }
        } finally {
            controller.stopAndRelease()
        }
    }

    /** Alpha is read with a title, an artist and an album, but no picture and no MusicBrainz ID. */
    private fun readAlphaWithoutACover() = runBlocking {
        val database = Library.database(context)
        val alpha = fileId(1)
        val tags = mapOf("TITLE" to listOf("Alpha"), "ARTIST" to listOf("Ann"), "ALBUM" to listOf("Red"))
        database.library().addTrack(TrackEntity.of(alpha, TrackTags.from(tags)))
        database.library().markRead(alpha, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null,
            bitDepth = null, channels = null, bitrate = null, tagEncoding = null, coverPresent = false, coverBytes = null,
            tagLengthMs = null)
        TrackDisplayWriter(database).refresh(listOf(alpha))
        Library.mirror(context).mirror()
    }

    /**
     * An own cover changed from the app's menus reaches an open page of Songs missing information and its number on the
     * Playlists tab without any other change (docs/library.md §17.4).
     */
    @Test
    fun anOwnCoverReachesSongsMissingInformationAndItsNumber() {
        readAlphaWithoutACover()
        MissingReasonChoice.chosen.value = MissingReasons.COVER
        try {
            tab(R.string.tab_playlists)
            tap(smartRow(R.string.smart_missing_info, 1))
            compose.waitUntilAtLeastOneExists(onPage(hasText("Alpha")), timeoutMillis = 10_000)

            runBlocking {
                val covers = Covers.own(context)
                covers.load()
                File(covers.dir.apply { mkdirs() }, "alpha-test.jpg").writeBytes(byteArrayOf(1, 2, 3))
                check(covers.setTrack(identity(1), CoverCopy("alpha-test.jpg", 10, 10)))
            }
            compose.waitUntilDoesNotExist(onPage(hasText("Alpha")), timeoutMillis = 10_000)

            // Back on the tab while its count is still collected: it follows too.
            Espresso.pressBack()
            compose.waitUntilAtLeastOneExists(smartRow(R.string.smart_missing_info, 0), timeoutMillis = 10_000)
        } finally {
            MissingReasonChoice.chosen.value = MissingReasons.DEFAULT
            runBlocking { Covers.own(context).removeTrack(identity(1)) }
        }
    }

    @Test
    fun songsMissingInformationShowItsChipsAndTheReasonsOfEachRow() {
        // Alpha is read: it has a title and an artist, but no album, no picture and no MusicBrainz ID.
        runBlocking {
            val database = Library.database(context)
            val alpha = fileId(1)
            database.library().addTrack(TrackEntity.of(alpha, TrackTags.from(mapOf("TITLE" to listOf("Alpha"), "ARTIST" to listOf("Ann")))))
            database.library().markRead(alpha, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null,
                bitDepth = null, channels = null, bitrate = null, tagEncoding = null, coverPresent = false, coverBytes = null,
                tagLengthMs = null)
            TrackDisplayWriter(database).refresh(listOf(alpha))
            // A run of the mirror tells the lists to read again, as the attach pass after a run of the tag pass does.
            Library.mirror(context).mirror()
        }
        tab(R.string.tab_playlists)
        tap(smartRow(R.string.smart_missing_info, 1))

        // Alpha's row, not the chip of the same name, shows the reason.
        val alphaWith = { reason: Int -> onPage(hasText("Alpha") and hasText(text(reason), substring = true)) }
        compose.waitUntilAtLeastOneExists(onPage(hasText("Alpha")), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(alphaWith(R.string.reason_album), timeoutMillis = 10_000)
        val chip = { reason: Int -> hasTestTag(SmartListTags.CHIP) and hasText(text(reason)) }
        compose.waitUntilAtLeastOneExists(chip(R.string.reason_title) and isSelected(), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(chip(R.string.reason_cover) and !isSelected(), timeoutMillis = 10_000)
        compose.waitUntilAtLeastOneExists(chip(R.string.reason_recording_id) and !isSelected(), timeoutMillis = 10_000)
        compose.waitForIdle()
        compose.onNode(alphaWith(R.string.reason_cover)).assertDoesNotExist()

        // The cover chip turned on: the row shows that reason too.
        tap(chip(R.string.reason_cover))
        compose.waitUntilAtLeastOneExists(alphaWith(R.string.reason_cover), timeoutMillis = 10_000)

        // Every chip off: nothing is listed, and the page says why.
        for (reason in listOf(R.string.reason_title, R.string.reason_artist, R.string.reason_album, R.string.reason_cover)) {
            tap(chip(reason))
        }
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.smart_missing_choose)), timeoutMillis = 10_000)
        compose.waitUntilDoesNotExist(onPage(hasText("Alpha")), timeoutMillis = 10_000)
    }
}
