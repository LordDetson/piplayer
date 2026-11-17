package io.github.lorddetson.piplayer.library.tagpass

import android.os.SystemClock
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * When background work that must not disturb playback may run, told by the app and the playback service: the tag pass
 * reads (docs/library.md §5.5) while the app shows or music plays, because otherwise Android freezes the process soon
 * and a read frozen in the middle could count as a hang; a batch's fingerprints decode ([awaitBatchOpen], TI §8)
 * whenever the device allows, since a batch runs as background WorkManager jobs. An underrun of the audio or a hot
 * device holds both for a minute more. A run waits here between files; nothing is cancelled, because cancelling a read
 * kills `:tagger`.
 */
class PassGate(private val now: () -> Long = SystemClock::elapsedRealtime) {

    private val uiVisible = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private val hot = MutableStateFlow(false)
    private val holds = MutableStateFlow(0L)

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

    /** Returns once the gate is closed, at once if it is; a long walk does not wait for the next file. */
    suspend fun awaitClosed() {
        combine(uiVisible, playing, hot, holds) { visible, plays, isHot, _ ->
            !(visible || plays) || isHot || heldUntil > now()
        }.first { it }
    }

    /**
     * Returns once a batch may decode its next fingerprint (TI §8): while the device is not hot, its status has stayed
     * below moderate for a minute and there has been no audio underrun for a minute. Music alone does not hold a batch,
     * whose fingerprints decode on a lane of one background thread, so at most one decoder runs for the batch while
     * music plays. The signals are the ones the tag pass shares: [setPlaying] matters only to it, [audioUnderrun] and
     * [setHot] to a batch too.
     */
    suspend fun awaitBatchOpen() {
        while (true) {
            combine(hot, holds) { isHot, _ -> !isHot }.first { it }
            val remaining = heldUntil - now()
            if (remaining <= 0) return
            delay(remaining)
        }
    }

    private fun hold() {
        heldUntil = max(heldUntil, now() + HOLD_MS)
        holds.value++
    }

    private companion object {
        /** As TI §8 asks of background work that gets in the way of playback. */
        const val HOLD_MS = 60_000L
    }
}
