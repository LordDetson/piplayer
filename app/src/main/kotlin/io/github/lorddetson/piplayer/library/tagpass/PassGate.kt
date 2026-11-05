package io.github.lorddetson.piplayer.library.tagpass

import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * When the tag pass may read (docs/library.md §5.5): while the app shows or music plays, because otherwise Android freezes
 * the process soon and a read frozen in the middle could count as a hang. An underrun of the audio or a hot device holds it
 * for a minute more. A run waits here between files; nothing is cancelled, because cancelling a read kills `:tagger`.
 */
class PassGate(private val now: () -> Long = System::currentTimeMillis) {

    private val uiVisible = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private val hot = MutableStateFlow(false)

    @Volatile
    private var heldUntil = 0L

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    fun setPlaying(playing: Boolean) {
        this.playing.value = playing
    }

    fun audioUnderrun() {
        hold()
    }

    fun setHot(hot: Boolean) {
        if (this.hot.value && !hot) hold()
        this.hot.value = hot
    }

    /** Returns once the gate is open. */
    suspend fun awaitOpen() {
        while (true) {
            combine(uiVisible, playing, hot) { visible, plays, isHot -> (visible || plays) && !isHot }.first { it }
            val remaining = heldUntil - now()
            if (remaining <= 0) return
            delay(remaining)
        }
    }

    private fun hold() {
        heldUntil = max(heldUntil, now() + HOLD_MS)
    }

    private companion object {
        /** As TI §8 asks of background work that gets in the way of playback. */
        const val HOLD_MS = 60_000L
    }
}
