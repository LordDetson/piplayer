package io.github.lorddetson.piplayer.playback.snippet

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.session.MediaController
import io.github.lorddetson.piplayer.playback.PlaybackPlayer
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import kotlinx.coroutines.flow.StateFlow

/** The snippet player on Media3 and the platform's audio focus (TI §6). */
internal class MediaSnippetPlayer(context: Context, main: Player) : SnippetPlayer {

    private val controller = SnippetController(
        PlayerMainPlayback(main, (main as? MediaController)?.let(::SessionMainHold)),
        AudioManagerSnippetFocus(context),
        ExoSnippetOutput(context.applicationContext),
    )

    override val state: StateFlow<SnippetState> get() = controller.state

    override val positionMs: Long get() = controller.positionMs

    override fun play(uri: Uri, durationMs: Long?) = controller.play(uri, durationMs)

    override fun stop() = controller.stop()
}

/**
 * The main player behind its controller. It counts as playing when it would go on if nothing paused it, also while it
 * buffers, as the playback service counts it when it is swiped away. Held back by another app's short interruption (a
 * prompt, a call) it does not count: Media3 resumes it when the interruption ends, and a pause and play from here would
 * lose that resume (TI §6). The pause is the playback service's [hold] when its session takes one, else a plain pause.
 */
internal class PlayerMainPlayback(private val player: Player, private val hold: MainHold? = null) : MainPlayback {

    /** Whether the last pause went through the hold, which its end then goes through too. */
    private var held = false

    override val isPlaying: Boolean
        get() = player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) &&
            player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

    override fun pause() {
        val hold = hold?.takeIf { it.available }
        held = hold != null
        if (hold != null) hold.hold() else player.pause()
    }

    override fun endPause(resume: Boolean) {
        val hold = hold?.takeIf { held }
        held = false
        when {
            hold != null -> hold.end(resume)
            resume -> player.play()
        }
    }
}

/**
 * The playback service's hold of its player for a snippet (TI §6): the service pauses the player for it and, at the
 * end, resumes it only when asked and nothing stopped it meanwhile.
 */
internal interface MainHold {
    /** Whether the main player's session takes the hold; a bare session without the playback service does not. */
    val available: Boolean

    fun hold()

    fun end(resume: Boolean)
}

/** The hold through PiPlayer's session command, which only PiPlayer's own screen may send. */
internal class SessionMainHold(private val controller: MediaController) : MainHold {

    override val available: Boolean
        get() = controller.isSessionCommandAvailable(PlaybackSessionCallback.SNIPPET_HOLD_COMMAND)

    override fun hold() {
        controller.sendCustomCommand(PlaybackSessionCallback.SNIPPET_HOLD_COMMAND, PlaybackSessionCallback.snippetHoldArgs(held = true))
    }

    override fun end(resume: Boolean) {
        controller.sendCustomCommand(
            PlaybackSessionCallback.SNIPPET_HOLD_COMMAND,
            PlaybackSessionCallback.snippetHoldArgs(held = false, resume = resume),
        )
    }
}

/** An `AUDIOFOCUS_GAIN_TRANSIENT` request for music, whose changes come on the main thread. */
internal class AudioManagerSnippetFocus(context: Context) : SnippetFocus {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onChange: (FocusChange) -> Unit): Boolean {
        abandon()
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                { change -> changeOf(change)?.let(onChange) },
                Handler(Looper.getMainLooper()),
            )
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        this.request = request
        return true
    }

    override fun abandon() {
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
    }

    private fun changeOf(change: Int): FocusChange? = when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.DUCK
        AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
        else -> null
    }
}

/**
 * A short-lived ExoPlayer for each snippet, built as the playback service's player so that it plays what the main
 * player plays, but with no media session and without Media3's focus handling: that would request a full
 * `AUDIOFOCUS_GAIN`, and the main player would lose its focus for good (TI §6). The window is the item's clipping, so
 * the snippet lasts exactly its 15 s and the player itself reports its end; a file that cannot seek to the window's
 * start fails.
 */
@OptIn(UnstableApi::class)
internal class ExoSnippetOutput(private val context: Context) : SnippetOutput<Uri> {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var window: SnippetWindow? = null

    /** The clipped item's position counts from the window's start. */
    override val positionMs: Long get() = player?.let { it.currentPosition + (window?.startMs ?: 0) } ?: 0

    override fun start(source: Uri, window: SnippetWindow, events: SnippetOutput.Events) {
        stop()
        val player = PlaybackPlayer.builder(context, handleAudioFocus = false).build()
        this.player = player
        this.window = window
        player.addListener(SnippetPlayerListener(events) { handler.post(it) })
        // Clipped here: the service's progressive sources do not apply an item's clipping configuration themselves.
        val file = PlaybackPlayer.mediaSources(context).createMediaSource(MediaItem.fromUri(source))
        val clipped = ClippingMediaSource.Builder(file)
            .setStartPositionMs(window.startMs)
            .setEndPositionMs(window.endMs)
            .build()
        player.setMediaSource(clipped)
        player.prepare()
        player.play()
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume
    }

    override fun stop() {
        player?.release()
        player = null
        window = null
    }
}

/**
 * What the snippet's player reports, each a moment later through [post], so that the controller never releases the
 * player inside the player's own callback.
 */
internal class SnippetPlayerListener(
    private val events: SnippetOutput.Events,
    private val post: (() -> Unit) -> Unit,
) : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) post(events::ended)
    }

    override fun onPlayerError(error: PlaybackException) {
        post(events::failed)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) post(events::becameNoisy)
    }
}
