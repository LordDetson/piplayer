package io.github.lorddetson.piplayer.playback.snippet

import androidx.media3.common.Player
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What the snippet's own player reports to the snippet's rules (TI §6), posted for a moment later. */
class SnippetPlayerListenerTest {

    private val log = mutableListOf<String>()
    private val posted = mutableListOf<() -> Unit>()

    private val listener = SnippetPlayerListener(
        object : SnippetOutput.Events {
            override fun ended() {
                log += "ended"
            }

            override fun failed() {
                log += "failed"
            }

            override fun becameNoisy() {
                log += "noisy"
            }
        },
        post = { posted += it },
    )

    private fun runPosted() {
        posted.forEach { it() }
        posted.clear()
    }

    @Test
    fun `the end of the window or the file is reported after the callback`() {
        listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertThat(posted).isEmpty()

        listener.onPlaybackStateChanged(Player.STATE_ENDED)

        assertThat(log).describedAs("inside the player's callback").isEmpty()
        runPosted()
        assertThat(log).containsExactly("ended")
    }

    @Test
    fun `unplugged headphones are reported and other pauses are not`() {
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertThat(posted).isEmpty()

        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)

        assertThat(log).isEmpty()
        runPosted()
        assertThat(log).containsExactly("noisy")
    }
}
