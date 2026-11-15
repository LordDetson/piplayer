package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The sleep timer, "stop after current track" (docs/playback.md §6.3 to §6.5) and the hold for a snippet of the review
 * card (TI §6) on the service's player. [publish] gets the state for PiPlayer's own controllers whenever it changes;
 * [store] keeps "stop after current track"; [stopService] stops the service when a hold that a swipe from recents kept
 * ends without a resume.
 */
@OptIn(UnstableApi::class)
class ServiceControls(
    private val player: ExoPlayer,
    private val store: StopAfterCurrentStore,
    private val publish: (Bundle) -> Unit,
    private val stopService: () -> Unit = {},
) : PlaybackControls {

    private var saved = store.read()
    private val endOfSong = EndOfSongPause(player, ::changed, stopAfterCurrent = saved)
    private val hold = SnippetHold()
    private val timer = SleepTimer(player, endOfSong, ::changed, onStoppedWhileHeld = hold::timerStopped)
    private var published = PlaybackExtras.NONE

    init {
        player.addListener(endOfSong)
        player.addListener(timer)
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady || !hold.held) return
                // Played by other means (its notification, another controller): the snippet loses its focus to it.
                hold.playedOtherwise()
                timer.endHold(resumes = true)
            }
        })
    }

    override fun setSleepTimer(durationMs: Long, finishTrack: Boolean) = timer.start(durationMs, finishTrack)

    override fun setStopAfterCurrent(enabled: Boolean) = endOfSong.setStopAfterCurrent(enabled)

    override fun holdForSnippet() {
        hold.hold()
        timer.hold()
        player.pause()
    }

    override fun endSnippetHold(resume: Boolean) {
        val end = hold.end(resume)
        if (end == SnippetHold.End.NONE) return
        timer.endHold(resumes = end == SnippetHold.End.RESUME)
        when (end) {
            SnippetHold.End.RESUME -> player.play()
            SnippetHold.End.STOP_SERVICE -> stopService()
            SnippetHold.End.STAY_PAUSED, SnippetHold.End.NONE -> Unit
        }
    }

    /** PiPlayer was swiped away from recents; true when the service stays for a snippet's hold. */
    fun taskRemoved(): Boolean = hold.taskRemoved()

    override fun extras(): Bundle = state().toBundle()

    fun release() = timer.release()

    private fun state() = PlaybackExtras(timer.deadlineMs, timer.finishTrack, endOfSong.stopAfterCurrent)

    private fun changed() {
        val state = state()
        if (state.stopAfterCurrent != saved) {
            saved = state.stopAfterCurrent
            store.save(saved)
        }
        if (state == published) return
        published = state
        publish(state.toBundle())
    }
}
