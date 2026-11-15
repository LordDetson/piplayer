package io.github.lorddetson.piplayer.playback.snippet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The part of a file a snippet plays (TI §6). */
data class SnippetWindow(val startMs: Long, val endMs: Long) {

    companion object {
        /** How long a snippet plays. */
        const val LENGTH_MS = 15_000L

        /** Tracks shorter than this play from the start; longer ones from one third. */
        const val FROM_START_BELOW_MS = 45_000L

        /** The window of a file of [durationMs]; an unknown length plays from the start. */
        fun of(durationMs: Long?): SnippetWindow = TODO()
    }
}

/** The main player as the snippet sees it: the playback service's player behind a MediaController. */
internal interface MainPlayback {
    /** Playing or about to play: it would go on if nothing paused it. */
    val isPlaying: Boolean

    fun pause()

    fun play()
}

/** How the audio focus changed for the snippet's request. */
internal enum class FocusChange { LOSS, LOSS_TRANSIENT, DUCK, GAIN }

/** The snippet's own audio focus request, `AUDIOFOCUS_GAIN_TRANSIENT`. */
internal interface SnippetFocus {
    /** Requests focus; true when granted, after which [onChange] hears its changes until [abandon]. */
    fun request(onChange: (FocusChange) -> Unit): Boolean

    fun abandon()
}

/** The short-lived player that plays a snippet of a [S]. */
internal interface SnippetOutput<S> {

    /** What the player reports about the snippet it plays. */
    interface Events {
        /** The window or the file has ended. */
        fun ended()

        fun failed()

        /** The audio output became noisy: the headphones were unplugged. */
        fun becameNoisy()
    }

    /** Plays [window] of [source] and reports to [events] until [stop]. */
    fun start(source: S, window: SnippetWindow, events: Events)

    fun setVolume(volume: Float)

    /** Stops and lets the player go. */
    fun stop()

    /** The position in the file; 0 when nothing plays. */
    val positionMs: Long
}

/**
 * The snippet's rules (TI §6) over its three parts: the main player, the focus request and the snippet's own player.
 * Not thread-safe: everything happens on the main thread.
 */
internal class SnippetController<S>(
    private val main: MainPlayback,
    private val focus: SnippetFocus,
    private val output: SnippetOutput<S>,
) {
    private val mutableState = MutableStateFlow<SnippetState>(SnippetState.Idle)

    val state: StateFlow<SnippetState> = mutableState.asStateFlow()

    val positionMs: Long get() = TODO()

    fun play(source: S, durationMs: Long?): Unit = TODO()

    fun stop(): Unit = TODO()

    companion object {
        /** The snippet's volume while a short prompt that allows ducking holds the focus. */
        const val DUCK_VOLUME = 0.2f
    }
}
