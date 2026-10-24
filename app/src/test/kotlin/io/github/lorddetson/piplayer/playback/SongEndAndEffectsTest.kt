package io.github.lorddetson.piplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SongEndRulesTest {

    @Test
    fun `a change of song by hand or a new queue turns stop after current off`() {
        assertThat(SongEndRules.clearsStopAfterCurrent(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)).isTrue()
        assertThat(SongEndRules.clearsStopAfterCurrent(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)).isTrue()
        assertThat(SongEndRules.clearsStopAfterCurrent(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)).isFalse()
        assertThat(SongEndRules.clearsStopAfterCurrent(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)).isFalse()
    }

    @Test
    fun `a song that ends on its own while the pause is set means the end was missed`() {
        assertThat(SongEndRules.missedEnd(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)).isTrue()
        assertThat(SongEndRules.missedEnd(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)).isTrue()
        assertThat(SongEndRules.missedEnd(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)).isFalse()
        assertThat(SongEndRules.missedEnd(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)).isFalse()
    }
}

class AudioEffectSessionTest {

    private val calls = mutableListOf<String>()
    private val session = AudioEffectSession(open = { calls += "open $it" }, close = { calls += "close $it" })

    @Test
    fun `the session opens once its ID is known and again on every play`() {
        session.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        session.onAudioSessionIdChanged(C.AUDIO_SESSION_ID_UNSET)
        session.onAudioSessionIdChanged(42)
        session.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        session.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

        assertThat(calls).containsExactly("open 42", "open 42")
    }

    @Test
    fun `a new session ID closes the old session`() {
        session.onAudioSessionIdChanged(42)
        session.onAudioSessionIdChanged(43)

        assertThat(calls).containsExactly("open 42", "close 42", "open 43")
    }

    @Test
    fun `release closes an opened session only`() {
        session.release()
        assertThat(calls).isEmpty()

        session.onAudioSessionIdChanged(42)
        session.release()
        session.release()
        assertThat(calls).containsExactly("open 42", "close 42")
    }
}
