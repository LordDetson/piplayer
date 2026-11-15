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
        fun of(durationMs: Long?): SnippetWindow {
            val startMs = if (durationMs != null && durationMs >= FROM_START_BELOW_MS) durationMs / 3 else 0
            return SnippetWindow(startMs, startMs + LENGTH_MS)
        }
    }
}

/** The main player as the snippet sees it: the playback service's player behind a MediaController. */
internal interface MainPlayback {
    /** Playing or about to play: it would go on if nothing paused it. */
    val isPlaying: Boolean

    /** Pauses it for the snippet. */
    fun pause()

    /** Ends the pause of [pause]: plays it again when [resume], else leaves it paused. */
    fun endPause(resume: Boolean)
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

    /** Whether the main player played before the first of the snippets now playing; it resumes only then. */
    private var mainWasPlaying = false

    /** Counts focus requests, so that a change heard after its request was abandoned changes nothing. */
    private var focusId = 0

    /** Counts snippets, so that a report of one that has ended or was replaced changes nothing. */
    private var snippetId = 0

    /** The snippet's volume: lowered while a prompt that allows ducking holds the focus. */
    private var volume = 1f

    private val playing: Boolean get() = mutableState.value is SnippetState.Playing

    val positionMs: Long get() = if (playing) output.positionMs else 0

    fun play(source: S, durationMs: Long?) {
        if (playing) {
            // The focus and the memory of the main player stay with the new snippet.
            output.stop()
        } else {
            mainWasPlaying = main.isPlaying
            if (mainWasPlaying) main.pause()
            volume = 1f
            val requested = ++focusId
            val granted = focus.request { change -> if (requested == focusId) onFocusChange(change) }
            if (!granted) {
                val paused = mainWasPlaying
                mainWasPlaying = false
                mutableState.value = SnippetState.Ended(SnippetEnd.FOCUS_REFUSED)
                if (paused) main.endPause(resume = true)
                return
            }
        }
        val started = ++snippetId
        val window = SnippetWindow.of(durationMs)
        // The player keeps the whole window, in case the known length is short; the progress ends with the file.
        val endMs = if (durationMs != null && durationMs > 0) minOf(window.endMs, durationMs) else window.endMs
        mutableState.value = SnippetState.Playing(window.startMs, endMs)
        output.start(
            source,
            window,
            object : SnippetOutput.Events {
                override fun ended() = endIfCurrent(started, SnippetEnd.FINISHED)

                override fun failed() = endIfCurrent(started, SnippetEnd.FAILED)

                override fun becameNoisy() = endIfCurrent(started, SnippetEnd.BECAME_NOISY)
            },
        )
        if (volume != 1f) output.setVolume(volume)
    }

    fun stop() {
        if (playing) end(SnippetEnd.CLOSED)
    }

    private fun onFocusChange(change: FocusChange) {
        if (!playing) return
        when (change) {
            FocusChange.LOSS, FocusChange.LOSS_TRANSIENT -> end(SnippetEnd.FOCUS_LOST)
            FocusChange.DUCK -> setVolume(DUCK_VOLUME)
            FocusChange.GAIN -> setVolume(1f)
        }
    }

    private fun setVolume(volume: Float) {
        this.volume = volume
        output.setVolume(volume)
    }

    private fun endIfCurrent(started: Int, reason: SnippetEnd) {
        if (started == snippetId && playing) end(reason)
    }

    private fun end(reason: SnippetEnd) {
        // New numbers silence the ended snippet's player and focus listener.
        snippetId++
        focusId++
        output.stop()
        // The focus goes before the main player asks for its own (TI §6).
        focus.abandon()
        val paused = mainWasPlaying
        mainWasPlaying = false
        mutableState.value = SnippetState.Ended(reason)
        if (paused) main.endPause(resume = reason.resumesMain)
    }

    private val SnippetEnd.resumesMain: Boolean
        get() = this == SnippetEnd.FINISHED || this == SnippetEnd.CLOSED || this == SnippetEnd.FAILED

    companion object {
        /** The snippet's volume while a short prompt that allows ducking holds the focus. */
        const val DUCK_VOLUME = 0.2f
    }
}
