package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.throughout
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Editing the queue through the playback service: "Play next", "Add to queue", removal and undo, moves, the up-next run
 * under shuffle, and an edited queue after the process is gone (docs/playback.md §9).
 */
@RunWith(AndroidJUnit4::class)
class QueueEditingTest {

    private val songs = SongSourceRule { (1L..6L).map { SongSourceRule.song(it, "Song $it", LONG_FIXTURE) } }
    private val store = PlaybackStateStore.get(TestFiles.targetContext)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(songs)

    private lateinit var controller: MediaController

    @Before
    fun startEmpty() {
        controller = connectController()
        onMain {
            controller.pause()
            controller.stop()
            controller.clearMediaItems()
            controller.shuffleModeEnabled = false
        }
        controller.waitUntil("the player is empty") { mediaItemCount == 0 && !shuffleModeEnabled }
        runBlocking { store.clear() }
    }

    @After
    fun cleanUp() {
        onMain { controller.shuffleModeEnabled = false }
        controller.stopAndRelease()
        runBlocking { store.clear() }
    }

    private fun send(command: SessionCommand, args: Bundle): SessionResult =
        onMain { controller.sendCustomCommand(command, args) }.get(10, TimeUnit.SECONDS)

    private fun add(vararg ids: Long, next: Boolean): SessionResult = send(
        PlaybackSessionCallback.ADD_TO_QUEUE_COMMAND,
        Bundle().apply {
            putLongArray(PlaybackSessionCallback.EXTRA_SONG_IDS, ids)
            putBoolean(PlaybackSessionCallback.EXTRA_NEXT, next)
        },
    )

    private fun remove(row: Int, mediaId: String): SessionResult = send(
        PlaybackSessionCallback.REMOVE_FROM_QUEUE_COMMAND,
        Bundle().apply {
            putInt(PlaybackSessionCallback.EXTRA_ROW, row)
            putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, mediaId)
        },
    )

    /** Undoes the removal whose result was [removal]. */
    private fun undo(removal: SessionResult): SessionResult = send(
        PlaybackSessionCallback.UNDO_REMOVE_COMMAND,
        Bundle().apply { putLong(PlaybackSessionCallback.EXTRA_TOKEN, removal.extras.getLong(PlaybackSessionCallback.EXTRA_TOKEN, -1)) },
    )

    private fun move(from: Int, to: Int, mediaId: String): SessionResult = send(
        PlaybackSessionCallback.MOVE_IN_QUEUE_COMMAND,
        Bundle().apply {
            putInt(PlaybackSessionCallback.EXTRA_FROM, from)
            putInt(PlaybackSessionCallback.EXTRA_TO, to)
            putString(PlaybackSessionCallback.EXTRA_MEDIA_ID, mediaId)
        },
    )

    /** The media IDs of the queue in list order. */
    private fun ids(): List<String> = onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }

    /** The media IDs of the queue in the order it plays. */
    private fun played(): List<String> = onMain {
        val timeline = controller.currentTimeline
        val shuffle = controller.shuffleModeEnabled
        val window = Timeline.Window()
        buildList {
            var index = timeline.getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET) {
                add(timeline.getWindow(index, window).mediaItem.mediaId)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
    }

    private fun startAtSong1() {
        controller.tap(QueueRequest(startSongId = 1, startRow = 0), "Song 1")
        controller.waitUntil("song 1 plays first of six") { isPlaying && currentMediaItem?.mediaId == "1" && mediaItemCount == 6 }
    }

    @Test
    fun playNextGoesRightAfterTheCurrentSong() {
        startAtSong1()

        val result = add(5, next = true)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(result.extras.getInt(PlaybackSessionCallback.EXTRA_ADDED)).isEqualTo(1)
        assertThat(result.extras.getString(PlaybackSessionCallback.EXTRA_TITLE)).isEqualTo("Song 5")
        controller.waitUntil("song 5 is next") { mediaItemCount == 7 }
        assertThat(ids()).containsExactly("1", "5/a1", "2", "3", "4", "5", "6")
        assertThat(onMain { controller.currentMediaItem?.mediaId }).isEqualTo("1")
    }

    @Test
    fun addToQueueGoesAfterTheUpNextRunUntilItPlays() {
        startAtSong1()
        add(5, next = true)
        add(6, 4, next = false)
        controller.waitUntil("three songs added") { mediaItemCount == 9 }
        assertThat(ids()).containsExactly("1", "5/a1", "6/a2", "4/a3", "2", "3", "4", "5", "6")

        onMain { controller.seekToNextMediaItem() }
        controller.waitUntil("the first added song plays") { currentMediaItem?.mediaId == "5/a1" }
        add(3, next = false)

        controller.waitUntil("song 3 added") { mediaItemCount == 10 }
        assertThat(ids()).containsExactly("1", "5/a1", "6/a2", "4/a3", "3/a4", "2", "3", "4", "5", "6")
    }

    @Test
    fun underShuffleAddedSongsGoToTheSamePlaceOfThePlayOrder() {
        startAtSong1()
        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        onMain { repeat(2) { controller.seekToNextMediaItem() } }
        val before = played()
        controller.waitUntil("the third song of the order plays") { currentMediaItem?.mediaId == before[2] }

        add(6, next = false)
        add(5, next = true)

        controller.waitUntil("two songs added") { mediaItemCount == 8 }
        assertThat(played()).containsExactlyElementsOf(before.take(3) + listOf("5/a2", "6/a1") + before.drop(3))
    }

    @Test
    fun theUpNextRunStaysNextWhenShuffleIsTurnedOn() {
        startAtSong1()
        add(5, 6, next = true)
        controller.waitUntil("two songs added") { mediaItemCount == 8 }

        onMain { controller.shuffleModeEnabled = true }

        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        assertThat(played().take(3)).containsExactly("1", "5/a1", "6/a2")
        assertThat(played()).hasSize(8)
    }

    @Test
    fun aRemovedEntryLeavesAndUndoPutsItBack() {
        startAtSong1()

        val removal = remove(row = 2, mediaId = "3")
        assertThat(removal.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        controller.waitUntil("song 3 is gone") { mediaItemCount == 5 }
        assertThat(ids()).containsExactly("1", "2", "4", "5", "6")

        assertThat(undo(removal).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        controller.waitUntil("song 3 is back") { mediaItemCount == 6 }
        assertThat(ids()).containsExactly("1", "2", "3", "4", "5", "6")
        // Once put back there is nothing more to undo.
        assertThat(undo(removal).resultCode).isNotEqualTo(SessionResult.RESULT_SUCCESS)
    }

    @Test
    fun aRemovalOfAnotherEntryThanTheRowShowsIsRefused() {
        startAtSong1()

        assertThat(remove(row = 2, mediaId = "4").resultCode).isNotEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(ids()).hasSize(6)
    }

    @Test
    fun removingTheCurrentSongPlaysTheNext() {
        startAtSong1()

        remove(row = 0, mediaId = "1")

        controller.waitUntil("song 2 plays") { mediaItemCount == 5 && currentMediaItem?.mediaId == "2" && isPlaying }
    }

    @Test
    fun withShuffleOffAMoveChangesTheList() {
        startAtSong1()

        assertThat(move(from = 4, to = 1, mediaId = "5").resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)

        controller.waitUntil("song 5 moved") { mediaItemCount == 6 && getMediaItemAt(1).mediaId == "5" }
        assertThat(ids()).containsExactly("1", "5", "2", "3", "4", "6")
    }

    @Test
    fun withShuffleOnAMoveChangesOnlyThePlayOrder() {
        startAtSong1()
        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        val before = played()

        assertThat(move(from = 4, to = 1, mediaId = before[4]).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)

        val expected = before.toMutableList().apply { add(1, removeAt(4)) }
        eventually("the play order changed") { played() == expected }
        assertThat(ids()).containsExactly("1", "2", "3", "4", "5", "6")
    }

    @Test
    fun anEmptyPlayerTakesAddedSongsPaused() {
        assertThat(add(2, 3, next = false).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)

        controller.waitUntil("the added songs are the queue") { mediaItemCount == 2 && playbackState == Player.STATE_READY }
        assertThat(ids()).containsExactly("2/a1", "3/a2")
        assertThat(onMain { controller.currentMediaItemIndex }).isZero()
        throughout("the queue stays paused", 1.seconds) { onMain { !controller.playWhenReady } }
    }

    @Test
    fun aListRequestAddsTheSongsOfTheList() {
        startAtSong1()

        val result = send(
            PlaybackSessionCallback.ADD_TO_QUEUE_COMMAND,
            Bundle().apply {
                putString(PlaybackSessionCallback.EXTRA_REQUEST, QueueRequest(startSongId = -1, startRow = 0).mediaId)
                putBoolean(PlaybackSessionCallback.EXTRA_NEXT, true)
            },
        )

        // The test's own source gives every song for any list.
        assertThat(result.extras.getInt(PlaybackSessionCallback.EXTRA_ADDED)).isEqualTo(6)
        controller.waitUntil("the list is added") { mediaItemCount == 12 }
        assertThat(ids().subList(1, 7)).containsExactly("1/a1", "2/a2", "3/a3", "4/a4", "5/a5", "6/a6")
    }

    @Test
    fun anEditedShuffledQueueComesBackWithItsEntriesOrderAndRun() {
        startAtSong1()
        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }
        add(6, next = true)
        add(5, next = false)
        controller.waitUntil("two songs added") { mediaItemCount == 8 }
        val third = ids()[3]
        remove(row = 3, mediaId = third)
        controller.waitUntil("an entry is gone") { mediaItemCount == 7 }
        onMain { controller.pause() }
        val entries = ids()
        val order = played()
        eventually("the edited queue is saved") {
            val saved = store.readBlocking()
            saved != null && saved.shuffle && saved.songId == 1L && saved.queueRevision > 0 &&
                store.readQueueBlocking()?.journal?.size == 3
        }

        // As after the end of the process: the player forgets its queue and its shuffle, the files stay.
        onMain {
            controller.stop()
            controller.clearMediaItems()
            controller.shuffleModeEnabled = false
        }
        controller.waitUntil("the player is empty") { mediaItemCount == 0 && !shuffleModeEnabled }

        ActivityScenario.launch(MainActivity::class.java).use {
            controller.waitUntil("the edited queue is back") { mediaItemCount == 7 && currentMediaItem?.mediaId == "1" }
            assertThat(ids()).isEqualTo(entries)
            assertThat(played()).isEqualTo(order)
            // The run is still there: a song added to the queue goes after both added songs.
            add(2, next = false)
            controller.waitUntil("song 2 added") { mediaItemCount == 8 }
            assertThat(played().take(4)).containsExactly("1", "6/a1", "5/a2", "2/a3")
        }
    }

    @Test
    fun anUndoPutsBackOnlyTheRemovalItNames() {
        startAtSong1()
        val first = remove(row = 2, mediaId = "3")
        controller.waitUntil("song 3 is gone") { mediaItemCount == 5 }
        val second = remove(row = 2, mediaId = "4")
        controller.waitUntil("song 4 is gone") { mediaItemCount == 4 }

        assertThat(undo(first).resultCode).isNotEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(undo(second).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        controller.waitUntil("song 4 is back") { mediaItemCount == 5 }
        assertThat(ids()).containsExactly("1", "2", "4", "5", "6")
    }

    @Test
    fun anUndoAfterShuffleWasTurnedOnIsRefused() {
        startAtSong1()
        val removal = remove(row = 2, mediaId = "3")
        controller.waitUntil("song 3 is gone") { mediaItemCount == 5 }

        onMain { controller.shuffleModeEnabled = true }
        controller.waitUntil("shuffle is on") { shuffleModeEnabled }

        assertThat(undo(removal).resultCode).isNotEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(ids()).hasSize(5)
    }

    @Test
    fun removingTheLastEntryForgetsTheSavedQueueAndUndoSavesItAgain() {
        add(2, next = false)
        controller.waitUntil("song 2 is the queue") { mediaItemCount == 1 && playbackState == Player.STATE_READY }
        eventually("the added queue is saved") {
            val saved = store.readBlocking()
            saved != null && saved.queueRevision > 0 && store.readQueueBlocking()?.revision == saved.queueRevision
        }

        val removal = remove(row = 0, mediaId = "2/a1")
        controller.waitUntil("the queue is empty") { mediaItemCount == 0 }
        eventually("the saved queue is forgotten") { store.readBlocking() == null && store.readQueueBlocking() == null }

        assertThat(undo(removal).resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        controller.waitUntil("song 2 is back") { mediaItemCount == 1 }
        eventually("the queue and its file are saved again") {
            val saved = store.readBlocking()
            val file = store.readQueueBlocking()
            saved != null && saved.queueRevision > 0 && file?.revision == saved.queueRevision && file.added.size == 1
        }
    }
}
