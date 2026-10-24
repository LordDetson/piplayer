package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * The sleep timer and "stop after current track" on the service's player (docs/playback.md §6.3 to §6.5). [publish]
 * gets the state for PiPlayer's own controllers whenever it changes; [store] keeps "stop after current track".
 */
@OptIn(UnstableApi::class)
class ServiceControls(
    player: ExoPlayer,
    private val store: StopAfterCurrentStore,
    private val publish: (Bundle) -> Unit,
) : PlaybackControls {

    private var saved = store.read()
    private val endOfSong = EndOfSongPause(player, ::changed, stopAfterCurrent = saved)
    private val timer = SleepTimer(player, endOfSong, ::changed)
    private var published = PlaybackExtras.NONE

    init {
        player.addListener(endOfSong)
        player.addListener(timer)
    }

    override fun setSleepTimer(durationMs: Long, finishTrack: Boolean) = timer.start(durationMs, finishTrack)

    override fun setStopAfterCurrent(enabled: Boolean) = endOfSong.setStopAfterCurrent(enabled)

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
