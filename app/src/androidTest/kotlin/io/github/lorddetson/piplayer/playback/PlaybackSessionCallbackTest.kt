package io.github.lorddetson.piplayer.playback

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.SHORT_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the session lets controllers do (docs/playback.md §1.4). Controllers of other apps cannot be made in a test, so
 * the callback is called directly with controller descriptions; the session it gets is a separate one on its own player.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSessionCallbackTest {

    private val context = TestFiles.targetContext
    private lateinit var callback: PlaybackSessionCallback
    private lateinit var store: PlaybackStateStore
    private lateinit var storeScope: CoroutineScope
    private val stateFile = File(context.cacheDir, "callback-state-${UUID.randomUUID()}.preferences_pb")
    private val queueRequests = CopyOnWriteArrayList<QueueState?>()

    @get:Rule
    val songs = SongSourceRule()
    private lateinit var session: MediaLibrarySession

    private val piPlayer = controller(context.packageName, Process.myUid(), trusted = true)
    private val systemUi = controller("com.android.systemui", Process.SYSTEM_UID, trusted = true)
    /**
     * The uid of an app that may not read music. Not simply the uid after PiPlayer's: on the owner's phone that one
     * belongs to an app that may, which package visibility hides from PiPlayer.
     */
    private val foreignUid = (Process.myUid() + 1..Process.LAST_APPLICATION_UID).first {
        TestFiles.targetContext.checkPermission(AudioPermission.name, -1, it) != PackageManager.PERMISSION_GRANTED
    }

    private val otherApp = controller("com.example.remote", foreignUid, trusted = false)

    /** The controller Media3 itself connects for the media notification, in PiPlayer's process. */
    private val notification = controller(
        context.packageName,
        Process.myUid(),
        trusted = true,
        hints = Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) },
    )

    /** These change how the player is set up, not what it plays, and stay with PiPlayer and the system. */
    private val setupCommands = listOf(
        Player.COMMAND_SET_AUDIO_ATTRIBUTES,
        Player.COMMAND_SET_VOLUME,
        Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        Player.COMMAND_SET_VIDEO_SURFACE,
    )

    @Before
    fun createSession() {
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PlaybackStateStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { stateFile }),
        )
        callback = PlaybackSessionCallback(context, store) { queueRequests += it }
        session = onMain {
            MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).setId("callback-test").build()
        }
    }

    @After
    fun releaseSession() {
        onMain {
            val player = session.player
            session.release()
            player.release()
            callback.release()
        }
        store.close()
        storeScope.cancel()
        stateFile.delete()
    }

    @Test
    fun piPlayerAndTheSystemGetEveryCommand() {
        for (controller in listOf(piPlayer, systemUi)) {
            val result = onMain { callback.onConnect(session, controller) }

            assertThat(result.isAccepted).describedAs(controller.packageName).isTrue()
            assertThat(result.availablePlayerCommands).describedAs(controller.packageName)
                .isEqualTo(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            val expected = if (controller == piPlayer) {
                ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(PlaybackSessionCallback.RESTORE_COMMAND)
                    .add(PlaybackSessionCallback.OPEN_COMMAND)
                    .build()
            } else {
                ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            }
            assertThat(result.availableSessionCommands).describedAs(controller.packageName).isEqualTo(expected)
        }
    }

    @Test
    fun onlyPiPlayersOwnScreenMaySendTheRestoreAndOpenCommands() {
        for (controller in listOf(systemUi, otherApp, notification)) {
            val result = onMain { callback.onConnect(session, controller) }

            assertThat(result.availableSessionCommands.commands)
                .describedAs(controller.toString())
                .doesNotContain(PlaybackSessionCallback.RESTORE_COMMAND)
                .doesNotContain(PlaybackSessionCallback.OPEN_COMMAND)
        }
    }

    @Test
    fun otherAppsControlPlaybackButCannotChangeHowThePlayerIsSetUpOrReadTheQueue() {
        val result = onMain { callback.onConnect(session, otherApp) }

        assertThat(result.isAccepted).isTrue()
        assertThat(result.availableSessionCommands).isEqualTo(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
        val commands = result.availablePlayerCommands
        for (command in setupCommands) {
            assertThat(commands.contains(command)).describedAs("command $command").isFalse()
        }
        // The queue is the user's music library: an app without access to music does not get to read it.
        assertThat(commands.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        val playbackCommands = ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().removeAll(*setupCommands.toIntArray())
            .remove(Player.COMMAND_GET_TIMELINE)
            .build()
        assertThat(commands).isEqualTo(playbackCommands)
        assertThat(commands.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).isTrue()
        val control = listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        )
        for (command in control) assertThat(commands.contains(command)).describedAs("command $command").isTrue()
    }

    @Test
    fun anotherAppPosingAsPiPlayerIsRefused() {
        val impostor = controller(context.packageName, foreignUid, trusted = false)

        val result = onMain { callback.onConnect(session, impostor) }

        assertThat(result.isAccepted).isFalse()
    }

    @Test
    fun piPlayerMayNameFiles() {
        val items = listOf(fileItem(LONG_FIXTURE))

        val added = onMain { callback.onAddMediaItems(session, piPlayer, items) }.get(5, TimeUnit.SECONDS)

        assertThat(added).isEqualTo(items)
    }

    @Test
    fun otherAppsCannotNameFiles() {
        val items = listOf(fileItem(LONG_FIXTURE))

        // Media3 hands the media notification's controller to the callback in place of a controller that says it is
        // the system UI, so that one counts as another app here.
        for (controller in listOf(otherApp, notification)) {
            val added = onMain { callback.onAddMediaItems(session, controller, items) }

            assertThatThrownBy { added.get(5, TimeUnit.SECONDS) }.describedAs(controller.toString())
                .isInstanceOf(ExecutionException::class.java)
                .hasCauseInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun anItemThatNamesNoFileCannotBePlayedYet() {
        val items = listOf(MediaItem.Builder().setMediaId("library:1").build())

        for (controller in listOf(piPlayer, otherApp)) {
            val added = onMain { callback.onAddMediaItems(session, controller, items) }

            assertThatThrownBy { added.get(5, TimeUnit.SECONDS) }.describedAs(controller.packageName)
                .hasCauseInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun theNotificationsControllerGetsEveryCommandButTheQueue() {
        val result = onMain { callback.onConnect(session, notification) }

        assertThat(result.isAccepted).isTrue()
        val commands = result.availablePlayerCommands
        assertThat(commands.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        assertThat(commands).isEqualTo(
            ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build(),
        )
    }

    @Test
    fun aCompactRequestExpandsIntoTheWholeListFromTheTappedSong() {
        songs.songs = listOf(
            SongSourceRule.song(30, "Charlie"), SongSourceRule.song(10, "alpha"), SongSourceRule.song(20, "Bravo"),
        )
        val request = QueueRequest(startSongId = 20, startRow = 1)

        val result = setItems(piPlayer, request)

        assertThat(result.mediaItems.map { it.mediaId }).containsExactly("10", "20", "30")
        assertThat(result.startIndex).isEqualTo(1)
        assertThat(result.startPositionMs).isEqualTo(C.TIME_UNSET)
        val bravo = result.mediaItems[1]
        val song = songs.songs.single { it.id == 20L }
        assertThat(bravo.localConfiguration?.uri.toString()).isEqualTo(song.uri)
        assertThat(bravo.mediaMetadata.title.toString()).isEqualTo("Bravo")
        assertThat(bravo.mediaMetadata.artist.toString()).isEqualTo(song.artist)
        assertThat(bravo.mediaMetadata.albumTitle.toString()).isEqualTo(song.album)
        assertThat(bravo.mediaMetadata.durationMs).isEqualTo(song.durationMs)
        assertThat(bravo.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
        assertThat(bravo.mediaMetadata.isPlayable).isTrue()
        // Nothing for other apps to read from the published items beyond what the notification shows.
        assertThat(bravo.requestMetadata).isEqualTo(MediaItem.RequestMetadata.EMPTY)
        assertThat(queueRequests.last()).isEqualTo(QueueState(request, emptyList()))
    }

    @Test
    fun aFileSetForgetsTheQueueThatWasSaved() {
        val items = listOf(fileItem(LONG_FIXTURE))

        onMain { callback.onSetMediaItems(session, piPlayer, items, 0, 0) }.get(5, TimeUnit.SECONDS)

        assertThat(queueRequests.last()).isNull()
    }

    @Test
    fun piPlayersScreenShowsTheSavedQueuePausedAtItsSongAndPosition() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        val saved = snapshot(songId = 2, row = 1, positionMs = 500)
        runBlocking { store.write(saved) }

        val result = onMain {
            callback.onCustomCommand(session, piPlayer, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
        }.get(10, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(onMain { session.player.mediaItemCount }).isEqualTo(3)
        assertThat(onMain { session.player.currentMediaItemIndex }).isEqualTo(1)
        assertThat(onMain { session.player.playWhenReady }).isFalse()
        eventually("the restored song is ready") { onMain { session.player.playbackState == Player.STATE_READY } }
        assertThat(onMain { session.player.currentPosition }).isBetween(400L, 700L)
        assertThat(queueRequests.last()).isEqualTo(QueueState(requireNotNull(saved.queue), emptyList()))
    }

    @Test
    fun aSavedSongThatIsGoneStartsAtItsRowWithoutItsOldPosition() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        runBlocking { store.write(snapshot(songId = 99, row = 1, positionMs = 1_500)) }

        onMain {
            callback.onCustomCommand(session, piPlayer, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
        }.get(10, TimeUnit.SECONDS)

        assertThat(onMain { session.player.currentMediaItemIndex }).isEqualTo(1)
        eventually("the fallback song is ready") { onMain { session.player.playbackState == Player.STATE_READY } }
        assertThat(onMain { session.player.currentPosition }).isLessThan(100L)
    }

    @Test
    fun onlyPiPlayersScreenMayRestoreTheQueue() {
        songs.songs = listOf(SongSourceRule.song(1, "A"))
        runBlocking { store.write(snapshot(songId = 1, row = 0)) }

        for (controller in listOf(systemUi, otherApp, notification)) {
            val result = onMain {
                callback.onCustomCommand(session, controller, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
            }.get(5, TimeUnit.SECONDS)

            assertThat(result.resultCode).describedAs(controller.toString())
                .isEqualTo(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            assertThat(onMain { session.player.mediaItemCount }).describedAs(controller.toString()).isZero()
        }
    }

    @Test
    fun theSystemRecentItemIsTheSavedSongWithItsPositionButNoQueue() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        runBlocking { store.write(snapshot(songId = 2, row = 1, positionMs = 500)) }

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = false) }
            .get(5, TimeUnit.SECONDS)

        assertThat(result.mediaItems).hasSize(1)
        val item = result.mediaItems.single()
        assertThat(item.mediaId).isEqualTo("2")
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("Title 2")
        assertThat(result.startIndex).isZero()
        assertThat(result.startPositionMs).isEqualTo(500L)
    }

    @Test
    fun aPlayRequestRebuildsTheWholeSavedQueueAtItsPosition() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        val saved = snapshot(songId = 2, row = 1, positionMs = 500)
        runBlocking { store.write(saved) }

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }
            .get(10, TimeUnit.SECONDS)

        assertThat(result.mediaItems.map { it.mediaId }).containsExactly("1", "2", "3")
        assertThat(result.startIndex).isEqualTo(1)
        assertThat(result.startPositionMs).isEqualTo(500L)
        assertThat(queueRequests.last()).isEqualTo(QueueState(requireNotNull(saved.queue), emptyList()))
    }

    @Test
    fun aPlayRequestWithoutASavedQueueFails() {
        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }

        assertThatThrownBy { result.get(5, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(UnsupportedOperationException::class.java)
    }

    private fun snapshot(songId: Long, row: Int, positionMs: Long = 0) = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 1, startRow = 0),
        songId = songId,
        row = row,
        positionMs = positionMs,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = "Title $songId",
        artist = "Artist",
        album = "Album",
        durationMs = 2_000,
        uri = null,
    )

    @Test
    fun aTappedSongThatIsGoneLeavesTheQueueStartingAtItsRow() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))

        val result = setItems(piPlayer, QueueRequest(startSongId = 99, startRow = 2))

        assertThat(result.mediaItems).hasSize(3)
        assertThat(result.startIndex).isEqualTo(2)
    }

    @Test
    fun theSystemMayAskForTheList() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))

        val result = setItems(systemUi, QueueRequest(startSongId = 2, startRow = 1))

        assertThat(result.mediaItems.map { it.mediaId }).containsExactly("1", "2")
        assertThat(result.startIndex).isEqualTo(1)
    }

    @Test
    fun appsWithoutAccessToMusicCannotAskForTheList() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        val request = MediaItem.Builder().setMediaId(QueueRequest(startSongId = 2, startRow = 1).mediaId).build()

        // Media3 hands the notification's controller in place of one that says it is the system UI.
        for (controller in listOf(otherApp, notification)) {
            val result = onMain { callback.onSetMediaItems(session, controller, listOf(request), C.INDEX_UNSET, 0) }

            assertThatThrownBy { result.get(5, TimeUnit.SECONDS) }.describedAs(controller.toString())
                .hasCauseInstanceOf(UnsupportedOperationException::class.java)
        }
        assertThat(songs.reads).isZero()
    }

    @Test
    fun anAppThatMayReadMusicItselfMayAskForTheList() {
        // An assumption would show up as a failure in this runner's report, so the test just stays empty here.
        if (context.checkPermission(AudioPermission.name, -1, Process.SHELL_UID) != PackageManager.PERMISSION_GRANTED) {
            Log.i("PlaybackSessionCallbackTest", "Skipped: the shell may not read music on this device")
            return
        }
        val shell = controller("com.android.shell", Process.SHELL_UID, trusted = false)
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))

        assertThat(onMain { callback.onConnect(session, shell) }.availablePlayerCommands
            .contains(Player.COMMAND_GET_TIMELINE)).isTrue()
        assertThat(setItems(shell, QueueRequest(startSongId = 2, startRow = 1)).mediaItems).hasSize(2)
    }

    @Test
    fun theNameOfAnAppThatMayReadMusicIsNotEnough() {
        // The permission belongs to the caller's uid; a package name can be claimed by anyone.
        val impostor = controller("com.android.shell", foreignUid, trusted = false)
        songs.songs = listOf(SongSourceRule.song(1, "A"))
        val request = MediaItem.Builder().setMediaId(QueueRequest(startSongId = 1, startRow = 0).mediaId).build()

        assertThat(onMain { callback.onConnect(session, impostor) }.availablePlayerCommands
            .contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        val result = onMain { callback.onSetMediaItems(session, impostor, listOf(request), C.INDEX_UNSET, 0) }
        assertThatThrownBy { result.get(5, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun theListShownOnScreenIsNotReadAgainForATap() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        SongList.load(context)
        val before = songs.reads

        setItems(piPlayer, QueueRequest(startSongId = 2, startRow = 1))

        assertThat(songs.reads).isEqualTo(before)
    }

    @Test
    fun aTappedSongMissingFromTheListMakesItReadAgain() {
        songs.songs = listOf(SongSourceRule.song(1, "A"))
        SongList.load(context)
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        val before = songs.reads

        val result = setItems(piPlayer, QueueRequest(startSongId = 2, startRow = 1))

        assertThat(songs.reads).isEqualTo(before + 1)
        assertThat(result.mediaItems.map { it.mediaId }).containsExactly("1", "2")
        assertThat(result.startIndex).isEqualTo(1)
    }

    @Test
    fun aListThatCannotBeReadIsRefused() {
        songs.failure = SecurityException("No access to music")
        val request = MediaItem.Builder().setMediaId(QueueRequest(startSongId = 1, startRow = 0).mediaId).build()

        val result = onMain { callback.onSetMediaItems(session, piPlayer, listOf(request), C.INDEX_UNSET, 0) }

        assertThatThrownBy { result.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(SecurityException::class.java)
    }

    @Test
    fun anEmptyListCannotBePlayed() {
        songs.songs = emptyList()
        val request = MediaItem.Builder().setMediaId(QueueRequest(startSongId = 1, startRow = 0).mediaId).build()

        val result = onMain { callback.onSetMediaItems(session, piPlayer, listOf(request), C.INDEX_UNSET, C.TIME_UNSET) }

        assertThatThrownBy { result.get(5, TimeUnit.SECONDS) }.isInstanceOf(ExecutionException::class.java)
            .hasCauseInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun setRequestsWithFilesFollowTheSameRulesAsAdds() {
        val items = listOf(fileItem(LONG_FIXTURE))

        val fromPiPlayer = onMain { callback.onSetMediaItems(session, piPlayer, items, 0, 0) }.get(5, TimeUnit.SECONDS)
        assertThat(fromPiPlayer.mediaItems).isEqualTo(items)

        for (controller in listOf(otherApp, notification)) {
            val set = onMain { callback.onSetMediaItems(session, controller, items, 0, 0) }
            assertThatThrownBy { set.get(5, TimeUnit.SECONDS) }.describedAs(controller.toString())
                .hasCauseInstanceOf(UnsupportedOperationException::class.java)
        }
    }

    @Test
    fun openedFilesGoRightAfterTheCurrentSongAndTheFirstOnePlays() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        val request = QueueRequest(startSongId = 2, startRow = 1)
        val library = setItems(piPlayer, request)
        onMain {
            session.player.setMediaItems(library.mediaItems, library.startIndex, 0)
            session.player.prepare()
        }
        val first = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))
        val second = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))

        val result = onMain {
            callback.onCustomCommand(
                session,
                piPlayer,
                PlaybackSessionCallback.OPEN_COMMAND,
                openArgs(first, second),
                null,
            )
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(result.extras.getInt(PlaybackSessionCallback.EXTRA_SKIPPED, 0)).isZero()
        assertThat(onMain { session.player.mediaItemCount }).isEqualTo(5)
        assertThat(onMain { session.player.currentMediaItemIndex }).isEqualTo(2)
        assertThat(onMain { session.player.playWhenReady }).isTrue()
        eventually("the opened file is ready") { onMain { session.player.playbackState == Player.STATE_READY } }
        assertThat(onMain { session.player.currentMediaItem?.mediaId }).isEqualTo(first.toString())
        assertThat(mediaIds())
            .containsExactly("1", "2", first.toString(), second.toString(), "3")
        val state = requireNotNull(queueRequests.last())
        assertThat(state.library).isEqualTo(request)
        assertThat(state.openItems.map { it.index to it.uri })
            .containsExactly(2 to first.toString(), 3 to second.toString())
        assertThat(state.openItems[0].file.title).isNotBlank()
    }

    @Test
    fun openedFilesStartAQueueWhenThePlayerIsEmpty() {
        val first = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))
        val second = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))

        val result = onMain {
            callback.onCustomCommand(
                session,
                piPlayer,
                PlaybackSessionCallback.OPEN_COMMAND,
                openArgs(first, second),
                null,
            )
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(onMain { session.player.mediaItemCount }).isEqualTo(2)
        assertThat(onMain { session.player.currentMediaItemIndex }).isZero()
        assertThat(onMain { session.player.playWhenReady }).isTrue()
        eventually("the first opened file is ready") {
            onMain { session.player.playbackState == Player.STATE_READY }
        }
        val state = requireNotNull(queueRequests.last())
        assertThat(state.library).isNull()
        assertThat(state.openItems.map { it.index to it.uri })
            .containsExactly(0 to first.toString(), 1 to second.toString())
    }

    @Test
    fun onlyPiPlayersScreenMayOpenFiles() {
        val file = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))
        for (controller in listOf(systemUi, otherApp, notification)) {
            val result = onMain {
                callback.onCustomCommand(
                    session,
                    controller,
                    PlaybackSessionCallback.OPEN_COMMAND,
                    openArgs(file),
                    null,
                )
            }.get(5, TimeUnit.SECONDS)

            assertThat(result.resultCode).describedAs(controller.toString())
                .isEqualTo(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            assertThat(onMain { session.player.mediaItemCount }).describedAs(controller.toString()).isZero()
        }
    }

    @Test
    fun openedFilesThatCannotBeReadAreReportedAndSkipped() {
        val valid = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))
        val missing = Uri.fromFile(File(context.cacheDir, "not-there.mp3"))

        val result = onMain {
            callback.onCustomCommand(
                session,
                piPlayer,
                PlaybackSessionCallback.OPEN_COMMAND,
                openArgs(valid, missing),
                null,
            )
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(result.extras.getInt(PlaybackSessionCallback.EXTRA_SKIPPED, 0)).isEqualTo(1)
        assertThat(onMain { session.player.mediaItemCount }).isEqualTo(1)
    }

    @Test
    fun openedFilesThatAllCannotBeReadAreRefused() {
        val result = onMain {
            callback.onCustomCommand(
                session,
                piPlayer,
                PlaybackSessionCallback.OPEN_COMMAND,
                openArgs(Uri.fromFile(File(context.cacheDir, "not-there.mp3"))),
                null,
            )
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_ERROR_IO)
        assertThat(result.extras.getInt(PlaybackSessionCallback.EXTRA_SKIPPED, 0)).isEqualTo(1)
        assertThat(onMain { session.player.mediaItemCount }).isZero()
        assertThat(queueRequests).isEmpty()
    }

    @Test
    fun theSavedQueueRestoresLibrarySongsAndOpenedFilesInOrder() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"), SongSourceRule.song(3, "C"))
        val first = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))
        val second = Uri.fromFile(TestFiles.copy(LONG_FIXTURE))
        val openItems = listOf(
            SavedOpenItem(1, OpenedFile(first.toString(), "Opened first", "Artist", "Album", 2_000)),
            SavedOpenItem(3, OpenedFile(second.toString(), "Opened second", "Artist", "Album", 60_000)),
        )
        val saved = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 1, startRow = 0),
            openItems = openItems,
            songId = -1,
            row = 3,
            positionMs = 500,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "Opened second",
            artist = "Artist",
            album = "Album",
            durationMs = 60_000,
            uri = second.toString(),
        )
        runBlocking { store.write(saved) }

        val result = onMain {
            callback.onCustomCommand(session, piPlayer, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(onMain { session.player.mediaItemCount }).isEqualTo(5)
        assertThat(mediaIds())
            .containsExactly("1", first.toString(), "2", second.toString(), "3")
        assertThat(onMain { session.player.currentMediaItemIndex }).isEqualTo(3)
        assertThat(onMain { session.player.playWhenReady }).isFalse()
        eventually("the current opened file is ready") {
            onMain { session.player.playbackState == Player.STATE_READY }
        }
        assertThat(onMain { session.player.currentPosition }).isBetween(400L, 700L)
        val state = requireNotNull(queueRequests.last())
        assertThat(state.library).isEqualTo(saved.queue)
        assertThat(state.openItems).isEqualTo(openItems)
    }

    @Test
    fun restoredOpenedFilesThatCannotBeReadAreLeftOutOfTheQueue() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        val readable = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))
        val missing = Uri.fromFile(File(context.cacheDir, "no-longer-granted.mp3"))
        val saved = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 1, startRow = 0),
            openItems = listOf(
                SavedOpenItem(1, OpenedFile(readable.toString(), "Readable", null, null, 2_000)),
                SavedOpenItem(2, OpenedFile(missing.toString(), "Gone", null, null, 2_000)),
            ),
            songId = 1,
            row = 0,
            positionMs = 0,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "A",
            artist = null,
            album = null,
            durationMs = 2_000,
            uri = null,
        )
        runBlocking { store.write(saved) }

        val result = onMain {
            callback.onCustomCommand(session, piPlayer, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
        }.get(15, TimeUnit.SECONDS)

        assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(mediaIds()).containsExactly("1", readable.toString(), "2")
        assertThat(onMain { session.player.currentMediaItemIndex }).isZero()
        val state = requireNotNull(queueRequests.last())
        assertThat(state.openItems.map { it.index to it.uri }).containsExactly(1 to readable.toString())
    }

    @Test
    fun aCurrentOpenedFileThatCannotBeReadFallsBackToItsRow() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        val readable = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))
        val missing = Uri.fromFile(File(context.cacheDir, "gone-current.mp3"))
        val saved = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 1, startRow = 0),
            openItems = listOf(
                SavedOpenItem(1, OpenedFile(readable.toString(), "Readable", null, null, 2_000)),
                SavedOpenItem(2, OpenedFile(missing.toString(), "Gone", null, null, 2_000)),
            ),
            songId = -1,
            row = 2,
            positionMs = 1_500,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "Gone",
            artist = null,
            album = null,
            durationMs = 2_000,
            uri = missing.toString(),
        )
        runBlocking { store.write(saved) }

        onMain {
            callback.onCustomCommand(session, piPlayer, PlaybackSessionCallback.RESTORE_COMMAND, Bundle.EMPTY, null)
        }.get(15, TimeUnit.SECONDS)

        assertThat(mediaIds()).containsExactly("1", readable.toString(), "2")
        assertThat(onMain { session.player.currentMediaItem?.mediaId }).isEqualTo("2")
        assertThat(onMain { session.player.currentPosition }).isLessThan(100L)
    }

    @Test
    fun aPlayRequestRebuildsOpenedFilesWithTheLibrary() {
        songs.songs = listOf(SongSourceRule.song(1, "A"), SongSourceRule.song(2, "B"))
        val opened = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))
        val saved = PlaybackSnapshot(
            queue = QueueRequest(startSongId = 1, startRow = 0),
            openItems = listOf(SavedOpenItem(1, OpenedFile(opened.toString(), "Opened", null, null, 2_000))),
            songId = -1,
            row = 1,
            positionMs = 500,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "Opened",
            artist = null,
            album = null,
            durationMs = 2_000,
            uri = opened.toString(),
        )
        runBlocking { store.write(saved) }

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }
            .get(15, TimeUnit.SECONDS)

        assertThat(result.mediaItems.map { it.mediaId })
            .containsExactly("1", opened.toString(), "2")
        assertThat(result.startIndex).isEqualTo(1)
        assertThat(result.startPositionMs).isEqualTo(500L)
        val state = requireNotNull(queueRequests.last())
        assertThat(state.openItems.map { it.index to it.uri }).containsExactly(1 to opened.toString())
    }

    @Test
    fun theSystemRecentItemCanBeAnOpenedFile() {
        val opened = Uri.fromFile(TestFiles.copy(SHORT_FIXTURE))
        val saved = PlaybackSnapshot(
            queue = null,
            openItems = listOf(SavedOpenItem(0, OpenedFile(opened.toString(), "Opened", null, null, 2_000))),
            songId = -1,
            row = 0,
            positionMs = 500,
            shuffle = false,
            repeatMode = PlaybackSnapshot.REPEAT_OFF,
            title = "Opened",
            artist = null,
            album = null,
            durationMs = 2_000,
            uri = opened.toString(),
        )
        runBlocking { store.write(saved) }

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = false) }
            .get(5, TimeUnit.SECONDS)

        assertThat(result.mediaItems).hasSize(1)
        assertThat(result.mediaItems.single().mediaId).isEqualTo(opened.toString())
        assertThat(result.mediaItems.single().mediaMetadata.title.toString()).isEqualTo("Opened")
        assertThat(result.startPositionMs).isEqualTo(500L)
    }

    private fun openArgs(vararg uris: Uri) = Bundle().apply {
        putStringArrayList(PlaybackSessionCallback.EXTRA_URIS, ArrayList(uris.map(Uri::toString)))
    }

    private fun mediaIds(): List<String> = onMain {
        (0 until session.player.mediaItemCount).map { row -> session.player.getMediaItemAt(row).mediaId }
    }

    private fun setItems(controller: MediaSession.ControllerInfo, request: QueueRequest): MediaItemsWithStartPosition {
        val item = MediaItem.Builder().setMediaId(request.mediaId).build()
        return onMain { callback.onSetMediaItems(session, controller, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }
            .get(10, TimeUnit.SECONDS)
    }

    private fun controller(packageName: String, uid: Int, trusted: Boolean, hints: Bundle = Bundle.EMPTY) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            /* pid= */ 0,
            uid,
            MediaLibraryInfo.VERSION_INT,
            /* interfaceVersion= */ 1,
            trusted,
            hints,
            /* isPackageNameVerified= */ true,
        )
}
