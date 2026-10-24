package io.github.lorddetson.piplayer.playback

import android.os.Bundle

/** What PiPlayer's own screen may set in the service besides the queue (docs/playback.md §6.5). */
interface PlaybackControls {

    /** A sleep timer of [durationMs]; 0 or less cancels it. */
    fun setSleepTimer(durationMs: Long, finishTrack: Boolean)

    fun setStopAfterCurrent(enabled: Boolean)

    /** The state for PiPlayer's own controllers, as [PlaybackExtras]. */
    fun extras(): Bundle

    companion object {
        /** No controls, for a session without them. */
        val NONE: PlaybackControls = object : PlaybackControls {
            override fun setSleepTimer(durationMs: Long, finishTrack: Boolean) = Unit
            override fun setStopAfterCurrent(enabled: Boolean) = Unit
            override fun extras(): Bundle = Bundle.EMPTY
        }
    }
}
