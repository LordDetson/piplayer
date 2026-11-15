package io.github.lorddetson.piplayer.playback.snippet

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a 15 s snippet of a file from the review card (TI §6) without disturbing the main player: the main player is
 * paused through [Player] (the card's MediaController of the playback service) and resumed afterwards if it was playing;
 * its queue, position and play statistics do not change. Use it on the main thread.
 */
interface SnippetPlayer {

    /** What the snippet does now, for the card's play button. */
    val state: StateFlow<SnippetState>

    /** Where the snippet is in the file, in milliseconds; 0 when none plays. */
    val positionMs: Long

    /**
     * Plays the snippet of [uri], a file of [durationMs] (null when unknown). A snippet that plays is replaced at once,
     * and what was remembered about the main player stays.
     */
    fun play(uri: Uri, durationMs: Long?)

    /** Ends the snippet, as when the card closes: the main player resumes if it was playing. Nothing when none plays. */
    fun stop()

    companion object {
        /** A snippet player that pauses and resumes [main]. */
        fun create(context: Context, main: Player): SnippetPlayer = MediaSnippetPlayer(context, main)
    }
}

/** The snippet's state. */
sealed interface SnippetState {

    /** No snippet has played yet. */
    data object Idle : SnippetState

    /** A snippet plays the part of the file from [startMs] to [endMs]. */
    data class Playing(val startMs: Long, val endMs: Long) : SnippetState

    /** The last snippet has ended for [reason]. */
    data class Ended(val reason: SnippetEnd) : SnippetState
}

/** Why a snippet ended; only the first three resume the main player (if it was playing). */
enum class SnippetEnd {
    /** It played its 15 s, or the file ended first. */
    FINISHED,

    /** [SnippetPlayer.stop]: the card closed or the user stopped it. */
    CLOSED,

    /** The snippet player could not play the file. */
    FAILED,

    /** Another player, a call or the main player itself took the audio focus. */
    FOCUS_LOST,

    /** The headphones were unplugged. */
    BECAME_NOISY,

    /** The system refused the focus request (during a call), so nothing played. */
    FOCUS_REFUSED,
}
