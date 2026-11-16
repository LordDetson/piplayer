package io.github.lorddetson.piplayer.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.PlaybackTesting
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.ui.player.EqualizerPanel
import io.github.lorddetson.piplayer.ui.player.PlayerTags
import io.github.lorddetson.piplayer.ui.player.TimerChoice
import io.github.lorddetson.piplayer.ui.player.TimerChoiceStore
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The app with the playback service: the mini player, Now Playing, back and predictive back, edge-to-edge, and the
 * picture embedded in a file there (docs/playback.md §3.1 to §3.4, §3.9; docs/library.md §13.5).
 *
 * Bravo is a file of the library, over a test index whose row is a real file of the media index, so that it plays and
 * the tag pass and the cover loader read its picture: since M3 step 21 the session gives a library song no picture of
 * the stream, so its cover comes only through the cover order (docs/library.md §13.1, §13.6). Alpha is a song of the
 * test's own list only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PiPlayerAppTest {

    private val context = TestFiles.targetContext
    private val index = FakeMediaIndex()

    /** Bravo as the library gives it once the tag pass has read its file. */
    private lateinit var bravo: Song
    private var inserted: Uri? = null

    /** Puts Bravo's file into the media index and the library before the list is read and the activity starts. */
    private val libraryFile = object : ExternalResource() {
        override fun before() {
            deleteLeftovers()
            val store = insert(NAME, TestFiles.assets.open(COVER_FIXTURE).use { it.readBytes() })
            index.volume().rows = listOf(
                row(store, "Bravo", path = FOLDER, name = NAME, artist = "Artist 2", album = "Album 2", durationMs = 2_000),
            )
            val id = runBlocking {
                Library.sync(context).sync()
                Library.sync(context).awaitIdle()
                Library.database(context).library().files().single { it.mediaStoreId == store }.id
            }
            // As on a device, the tag pass finds the picture, so the cover order takes it at its embedded step.
            eventually("the tag pass has found Bravo's picture", 30.seconds) {
                runBlocking { Covers.facts(context).file(id)?.embedded == true }
            }
            bravo = checkNotNull(SongList.songsById(context, listOf(id))[id]) { "No song $id" }
        }

        override fun after() {
            inserted?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    private val songs = SongSourceRule {
        listOf(
            // An ID the test's library does not hold, so that no file of the library stands behind Alpha.
            SongSourceRule.song(1, "Alpha", PlaybackTesting.LONG_FIXTURE).copy(id = ALPHA_ID),
            bravo,
        )
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index }).around(libraryFile).around(songs).around(compose)

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private val inMiniPlayer = hasAnyAncestor(hasTestTag(PlayerTags.MINI_PLAYER))
    private val inNowPlaying = hasAnyAncestor(hasTestTag(PlayerTags.NOW_PLAYING))
    private val cover = hasTestTag(PlayerTags.COVER)
    private val inSongList = hasAnyAncestor(hasTestTag(SongsTags.LIST))

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** Taps [title] in the list and pauses once it plays, so that nothing moves on screen. */
    private fun playAndPause(title: String) {
        /*
         * A restored mini player can show the same title as the list (docs/playback.md §4.4); only the list row is
         * the tap target here.
         */
        compose.waitUntilExactlyOneExists(hasText(title) and inSongList, timeoutMillis = 10_000)
        compose.onNode(hasText(title) and inSongList).performClick()
        controller.waitUntil("$title plays") { isPlaying && mediaMetadata.title == title }
        onMain { controller.pause() }
        compose.waitUntilExactlyOneExists(hasTestTag(PlayerTags.MINI_PLAYER), timeoutMillis = 10_000)
    }

    private fun openNowPlaying() {
        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing))).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 10_000)
    }

    private fun nowPlayingBounds(): Rect =
        compose.onNodeWithTag(PlayerTags.NOW_PLAYING).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    private fun back(event: (dispatcher: androidx.activity.OnBackPressedDispatcher) -> Unit) =
        compose.runOnUiThread { event(compose.activity.onBackPressedDispatcher) }

    private fun gesture(progress: Float) = BackEventCompat(10f, 800f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun aTappedSongBringsUpTheMiniPlayerWhichOpensNowPlaying() {
        playAndPause("Alpha")

        compose.onNode(hasClickLabel(text(R.string.player_open_now_playing))).assert(hasText("Alpha"))
        openNowPlaying()

        compose.onNodeWithText("Album 1").assertIsDisplayed()
        compose.waitUntilExactlyOneExists(hasText("MP3 · 128 kbit/s · 44.1 kHz"), timeoutMillis = 10_000)
        back { it.onBackPressed() }
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)
        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test
    fun thePredictiveBackGestureMovesNowPlayingAndCancellingKeepsIt() {
        playAndPause("Alpha")
        openNowPlaying()
        compose.waitForIdle()
        val full = nowPlayingBounds()

        back {
            it.dispatchOnBackStarted(gesture(0f))
            it.dispatchOnBackProgressed(gesture(0.5f))
        }
        compose.waitForIdle()
        assertThat(nowPlayingBounds()).isNotEqualTo(full)

        back { it.dispatchOnBackCancelled() }
        compose.waitUntil(timeoutMillis = 5_000) { nowPlayingBounds() == full }

        back {
            it.dispatchOnBackStarted(gesture(0f))
            it.dispatchOnBackProgressed(gesture(0.5f))
            it.onBackPressed()
        }
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)
        compose.onNodeWithTag(PlayerTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test
    fun backOnTheListIsLeftToTheSystem() {
        playAndPause("Alpha")
        openNowPlaying()
        assertThat(compose.runOnUiThread { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }).isTrue()

        compose.onNode(hasContentDescription(text(R.string.player_close_now_playing))).performClick()
        compose.waitUntilDoesNotExist(hasTestTag(PlayerTags.NOW_PLAYING), timeoutMillis = 5_000)

        assertThat(compose.runOnUiThread { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() }).isFalse()
    }

    @Test
    fun theMiniPlayerReachesTheBottomEdgeWithItsControlsAboveTheNavigationBar() {
        playAndPause("Alpha")
        val insets = compose.runOnUiThread { ViewCompat.getRootWindowInsets(compose.activity.window.decorView) }!!
        val navigationBarPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        assumeTrue("this device has a navigation bar at the bottom", navigationBarPx > 0)
        val navigationBar: Dp = with(compose.density) { navigationBarPx.toDp() }

        val window = compose.onRoot().getBoundsInRoot()
        val bar = compose.onNodeWithTag(PlayerTags.MINI_PLAYER).getBoundsInRoot()
        val play = compose.onNode(hasContentDescription(text(R.string.player_play)) and inMiniPlayer).getBoundsInRoot()

        assertThat(bar.bottom.value).isCloseTo(window.bottom.value, org.assertj.core.data.Offset.offset(1f))
        assertThat(play.bottom.value).isLessThanOrEqualTo((window.bottom - navigationBar + 0.5.dp).value)
    }

    /** The color at the middle of the one picture [matcher] finds; null without exactly one, or while it goes away. */
    private fun centerColor(matcher: SemanticsMatcher): Int? = try {
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true)
        if (nodes.fetchSemanticsNodes().size != 1) {
            null
        } else {
            nodes[0].captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }.toArgb()
        }
    } catch (_: AssertionError) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    /** JPEG and scaling move each channel a little. */
    private fun near(color: Int, expected: Int) =
        listOf(16, 8, 0).all { shift -> kotlin.math.abs((color shr shift and 0xFF) - (expected shr shift and 0xFF)) <= 12 }

    /** Waits until [matcher] finds one cover whose middle has the color of the fixture's front cover. */
    private fun awaitFrontCover(where: String, matcher: SemanticsMatcher) {
        var shown: Int? = null
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                shown = centerColor(matcher)
                shown?.let { near(it, FRONT_COLOR) } == true
            }
        } catch (e: ComposeTimeoutException) {
            val seen = shown?.let { "a cover of %08x".format(it) } ?: "no single cover"
            throw AssertionError("The front cover does not show in $where: $seen", e)
        }
    }

    @Test
    fun aLibrarySongsEmbeddedPictureShowsInTheMiniPlayerAndOnNowPlaying() {
        playAndPause("Bravo")
        assertThat(onMain { controller.currentMediaItem?.mediaId }?.let(QueueIds::fileId)).describedAs("a song of the library")
            .isEqualTo(bravo.id)

        awaitFrontCover("the mini player", cover and inMiniPlayer)
        openNowPlaying()
        awaitFrontCover("Now Playing", cover and inNowPlaying)
    }

    @Test
    fun aFileOpenedFromAnotherAppShowsThePictureThePlayerReadFromIt() {
        val file = Uri.fromFile(TestFiles.copy(COVER_FIXTURE)).toString()
        // What the activity sends for an open-with intent (docs/playback.md §5.1).
        val arguments = Bundle().apply { putStringArrayList(PlaybackSessionCallback.EXTRA_URIS, arrayListOf(file)) }

        val result = onMain { controller.sendCustomCommand(PlaybackSessionCallback.OPEN_COMMAND, arguments) }.get(10, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        controller.waitUntil("the opened file is the current item") { currentMediaItem?.mediaId == file }
        assertThat(QueueIds.fileId(file)).describedAs("no file of the library").isNull()
        awaitFrontCover("the mini player", cover and inMiniPlayer)
        openNowPlaying()
        awaitFrontCover("Now Playing", cover and inNowPlaying)
    }

    @Test
    fun theEqualizerButtonOpensTheDevicesPanelForPiPlayersSession() {
        assumeTrue("this device has an effect panel", EqualizerPanel.isAvailable(compose.activity))
        playAndPause("Alpha")
        openNowPlaying()
        val sessionId = onMain { controller.audioSessionId }
        var started: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) {
                    started = intent
                    Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                } else {
                    null
                }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            compose.onNode(hasContentDescription(text(R.string.player_equalizer))).performClick()

            compose.waitUntil(timeoutMillis = 5_000) { started != null }
            assertThat(started!!.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)).isEqualTo(sessionId).isNotZero()
            assertThat(started!!.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)).isEqualTo(compose.activity.packageName)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun theTimerSheetKeepsItsChoiceInTheSettings() {
        TimerChoiceStore.clearForTests(compose.activity)
        playAndPause("Alpha")
        openNowPlaying()
        val timer = hasContentDescription(text(R.string.player_sleep_timer))
        compose.onNode(timer).performClick()

        compose.onNodeWithText("1:00").performClick()
        compose.onNodeWithText(text(R.string.timer_finish_track)).performClick()
        compose.onNodeWithText(text(R.string.timer_finish_track)).assertIsOn()
        compose.onNodeWithText("1:00").assertIsSelected()
        // Closed without starting a timer, by the back key, which the sheet's own window takes.
        Espresso.pressBack()
        compose.waitUntilDoesNotExist(hasText(text(R.string.timer_start)), timeoutMillis = 5_000)

        assertThat(TimerChoiceStore(compose.activity).read()).isEqualTo(TimerChoice(hours = 1, minutes = 0, finishTrack = true))
        compose.onNode(timer).performClick()
        compose.onNodeWithText("1:00").assertIsSelected()
        compose.onNodeWithText(text(R.string.timer_finish_track)).assertIsOn()
        TimerChoiceStore.clearForTests(compose.activity)
    }

    /** Puts [bytes] into the media index as the app's own file [name] (no permission needed), and gives its ID there. */
    private fun insert(name: String, bytes: ByteArray): Long {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(FakeMediaIndex.PRIMARY), values)) { "Could not insert $name" }
        inserted = uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return ContentUris.parseId(uri)
    }

    /** Files of an earlier run that died before its clean-up; the app may delete its own files. */
    private fun deleteLeftovers() {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(FOLDER, context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
            }
        }
    }

    private companion object {
        const val FOLDER = "Music/PiPlayer app test/"
        const val NAME = "bravo.mp3"

        /** An MP3 whose only picture is a front cover (scripts/generate_playback_fixtures.py). */
        const val COVER_FIXTURE = "playback/cover-front.mp3"

        /** The color of the fixtures' front cover. */
        const val FRONT_COLOR = 0xFF3D5AFE.toInt()

        const val ALPHA_ID = 1_000_000L
    }
}
