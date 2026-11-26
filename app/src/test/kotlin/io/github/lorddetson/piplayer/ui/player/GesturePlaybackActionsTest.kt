package io.github.lorddetson.piplayer.ui.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GesturePlaybackActionsTest {
    private val calls = mutableListOf<Pair<String, Any?>>()
    private var commands = emptySet<Int>()
    private var position = 0L
    private var duration = 60_000L
    private var seekable = true
    private var speed = 1f
    private var volume = 0.5f
    private var shuffle = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var currentTrack = 1
    private var nextTrack = 2
    private var previousTrack = 0

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
        when (method.name) {
            "isCommandAvailable" -> arguments?.single() in commands
            "getCurrentPosition" -> position
            "getDuration" -> duration
            "isCurrentMediaItemSeekable" -> seekable
            "getPlaybackParameters" -> PlaybackParameters(speed, 1.2f)
            "getVolume" -> volume
            "getRepeatMode" -> repeat
            "getShuffleModeEnabled" -> shuffle
            "getCurrentMediaItemIndex" -> currentTrack
            "getNextMediaItemIndex" -> nextTrack
            "getPreviousMediaItemIndex" -> previousTrack
            "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
            "getPlaybackState" -> Player.STATE_READY
            "getPlayWhenReady" -> true
            "pause", "seekTo", "seekToNextMediaItem", "seekToPreviousMediaItem", "setPlaybackSpeed",
            "setVolume", "setRepeatMode", "setShuffleModeEnabled" -> {
                calls += method.name to arguments?.singleOrNull()
                null
            }
            else -> error("Unexpected player call ${method.name}")
        }
    } as Player

    private fun play(id: UiGestureActionId, seconds: Int? = null) =
        performPlaybackGesture(UiGestureAction(id, amountSeconds = seconds), player)

    @Test
    fun `seek uses the configured offset and clamps to the track bounds`() {
        commands = setOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        position = 52_000
        assertThat(play(UiGestureActionId.SEEK_FORWARD, 15)).isTrue()
        position = 9_000
        assertThat(play(UiGestureActionId.SEEK_BACKWARD, 5)).isTrue()
        assertThat(play(UiGestureActionId.SEEK_BACKWARD)).isTrue()
        assertThat(calls).containsExactly("seekTo" to 60_000L, "seekTo" to 4_000L, "seekTo" to 0L)
    }

    @Test
    fun `seek on a nonseekable source or without a controller command does nothing`() {
        assertThat(play(UiGestureActionId.SEEK_FORWARD)).isFalse()
        commands = setOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        seekable = false
        assertThat(play(UiGestureActionId.SEEK_FORWARD)).isFalse()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `speed changes in steps and reset returns normal speed`() {
        commands = setOf(Player.COMMAND_SET_SPEED_AND_PITCH)
        assertThat(play(UiGestureActionId.SPEED_UP)).isTrue()
        speed = 0.3f
        assertThat(play(UiGestureActionId.SPEED_DOWN)).isTrue()
        assertThat(play(UiGestureActionId.SPEED_RESET)).isTrue()
        assertThat(calls).containsExactly("setPlaybackSpeed" to 1.1f, "setPlaybackSpeed" to 0.25f, "setPlaybackSpeed" to 1f)
    }

    @Test
    fun `volume affects only the player and stays within its range`() {
        commands = setOf(Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME)
        volume = 0.97f
        assertThat(play(UiGestureActionId.VOLUME_UP)).isTrue()
        volume = 0.04f
        assertThat(play(UiGestureActionId.VOLUME_DOWN)).isTrue()
        assertThat(calls).containsExactly("setVolume" to 1f, "setVolume" to 0f)
    }

    @Test
    fun `previous and next select neighboring tracks without restarting this one`() {
        commands = setOf(Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        position = 30_000
        assertThat(play(UiGestureActionId.PREVIOUS_TRACK)).isTrue()
        assertThat(play(UiGestureActionId.NEXT_TRACK)).isTrue()
        previousTrack = C.INDEX_UNSET
        nextTrack = currentTrack
        assertThat(play(UiGestureActionId.PREVIOUS_TRACK)).isFalse()
        assertThat(play(UiGestureActionId.NEXT_TRACK)).isFalse()
        assertThat(calls).containsExactly("seekToPreviousMediaItem" to null, "seekToNextMediaItem" to null)
    }

    @Test
    fun `playback and order actions honor the controller commands`() {
        assertThat(play(UiGestureActionId.PLAY_PAUSE)).isFalse()
        assertThat(play(UiGestureActionId.TOGGLE_SHUFFLE)).isFalse()
        assertThat(play(UiGestureActionId.CYCLE_REPEAT)).isFalse()
        commands = setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
        repeat = Player.REPEAT_MODE_ALL
        assertThat(play(UiGestureActionId.PLAY_PAUSE)).isTrue()
        assertThat(play(UiGestureActionId.TOGGLE_SHUFFLE)).isTrue()
        assertThat(play(UiGestureActionId.CYCLE_REPEAT)).isTrue()
        assertThat(calls).containsExactly("pause" to null, "setShuffleModeEnabled" to true, "setRepeatMode" to Player.REPEAT_MODE_ONE)
    }

    @Test
    fun `screen commands dispatch exactly once when the screen provides them`() {
        assertThat(performPlaybackGesture(UiGestureAction(UiGestureActionId.OPEN_QUEUE), null)).isFalse()
        val actions = mutableListOf<String>()
        assertThat(performPlaybackGesture(
            UiGestureAction(UiGestureActionId.OPEN_QUEUE), null,
            onOpenQueue = { actions += "queue" },
        )).isTrue()
        assertThat(performPlaybackGesture(
            UiGestureAction(UiGestureActionId.TOGGLE_FAVORITE), player,
            onToggleFavorite = { actions += "favorite" },
        )).isTrue()
        assertThat(performPlaybackGesture(
            UiGestureAction(UiGestureActionId.SLEEP_TIMER), null,
            onOpenSleepTimer = { actions += "timer" },
        )).isTrue()
        assertThat(actions).containsExactly("queue", "favorite", "timer")
        assertThat(calls).isEmpty()
    }
}
