package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
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
    private val callback = PlaybackSessionCallback(context.packageName)
    private lateinit var session: MediaLibrarySession

    private val piPlayer = controller(context.packageName, Process.myUid(), trusted = true)
    private val systemUi = controller("com.android.systemui", Process.SYSTEM_UID, trusted = true)
    private val otherApp = controller("com.example.remote", Process.myUid() + 1, trusted = false)

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
        session = onMain {
            MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).setId("callback-test").build()
        }
    }

    @After
    fun releaseSession() = onMain {
        val player = session.player
        session.release()
        player.release()
    }

    @Test
    fun piPlayerAndTheSystemGetEveryCommand() {
        for (controller in listOf(piPlayer, systemUi)) {
            val result = onMain { callback.onConnect(session, controller) }

            assertThat(result.isAccepted).describedAs(controller.packageName).isTrue()
            assertThat(result.availablePlayerCommands).describedAs(controller.packageName)
                .isEqualTo(ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            assertThat(result.availableSessionCommands).describedAs(controller.packageName)
                .isEqualTo(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
        }
    }

    @Test
    fun otherAppsControlPlaybackButCannotChangeHowThePlayerIsSetUp() {
        val result = onMain { callback.onConnect(session, otherApp) }

        assertThat(result.isAccepted).isTrue()
        assertThat(result.availableSessionCommands).isEqualTo(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
        val commands = result.availablePlayerCommands
        for (command in setupCommands) {
            assertThat(commands.contains(command)).describedAs("command $command").isFalse()
        }
        val playbackCommands = ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().removeAll(*setupCommands.toIntArray())
            .build()
        assertThat(commands).isEqualTo(playbackCommands)
        val control = listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
        )
        for (command in control) assertThat(commands.contains(command)).describedAs("command $command").isTrue()
    }

    @Test
    fun anotherAppPosingAsPiPlayerIsRefused() {
        val impostor = controller(context.packageName, Process.myUid() + 1, trusted = false)

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
