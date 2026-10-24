package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Announces PiPlayer's audio session to effect apps (docs/playback.md §6.6): [open] once the player has its session ID
 * and again whenever playback starts, so that an effect app that was restarted takes it back; [close] for a session
 * that is replaced, and when [release]d. A pause keeps the session open, so that effects are there at once on play.
 */
@OptIn(UnstableApi::class)
class AudioEffectSession(private val open: (sessionId: Int) -> Unit, private val close: (sessionId: Int) -> Unit) :
    Player.Listener {

    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var opened = C.AUDIO_SESSION_ID_UNSET

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (opened != C.AUDIO_SESSION_ID_UNSET && opened != audioSessionId) {
            close(opened)
            opened = C.AUDIO_SESSION_ID_UNSET
        }
        sessionId = audioSessionId
        announce()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) announce()
    }

    fun release() {
        if (opened != C.AUDIO_SESSION_ID_UNSET) close(opened)
        opened = C.AUDIO_SESSION_ID_UNSET
    }

    private fun announce() {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        open(sessionId)
        opened = sessionId
    }
}
