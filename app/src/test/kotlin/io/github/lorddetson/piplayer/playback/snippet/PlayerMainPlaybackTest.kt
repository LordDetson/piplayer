package io.github.lorddetson.piplayer.playback.snippet

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The main player as the snippet sees it (TI §6), over a fake [Player] and a fake hold of the playback service. */
class PlayerMainPlaybackTest {

    private val log = mutableListOf<String>()

    private var playWhenReady = true
    private var playbackState = Player.STATE_READY
    private var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE

    /** Only what the snippet may ask of the main player answers; anything else fails the test. */
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getPlayWhenReady" -> playWhenReady
            "getPlaybackState" -> playbackState
            "getPlaybackSuppressionReason" -> suppression
            "pause", "play" -> {
                log += "player.${method.name}"
                null
            }
            else -> throw UnsupportedOperationException(method.name)
        }
    } as Player

    private val hold = object : MainHold {
        override var available = true

        override fun hold() {
            log += "hold"
        }

        override fun end(resume: Boolean) {
            log += "end resume=$resume"
        }
    }

    private fun isPlaying() = PlayerMainPlayback(player).isPlaying

    @Test
    fun `it plays with play requested while ready or buffering`() {
        assertThat(isPlaying()).isTrue()
        playbackState = Player.STATE_BUFFERING
        assertThat(isPlaying()).describedAs("buffering").isTrue()
    }

    @Test
    fun `an ended or idle player or one paused does not play`() {
        playbackState = Player.STATE_ENDED
        assertThat(isPlaying()).describedAs("an ended queue with play still requested").isFalse()
        playbackState = Player.STATE_IDLE
        assertThat(isPlaying()).describedAs("idle").isFalse()
        playbackState = Player.STATE_READY
        playWhenReady = false
        assertThat(isPlaying()).describedAs("paused").isFalse()
    }

    @Test
    fun `a player held back by a short interruption does not count and other suppressions do`() {
        suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        assertThat(isPlaying()).describedAs("a call or a prompt").isFalse()
        suppression = Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
        assertThat(isPlaying()).describedAs("an unsuitable output").isTrue()
    }

    @Test
    fun `the pause is the playback service's hold and its end says whether to resume`() {
        val main = PlayerMainPlayback(player, hold)

        main.pause()
        main.endPause(resume = true)
        main.pause()
        main.endPause(resume = false)

        assertThat(log).containsExactly("hold", "end resume=true", "hold", "end resume=false")
    }

    @Test
    fun `without the service's hold the player itself pauses and plays`() {
        hold.available = false
        for (main in listOf(PlayerMainPlayback(player), PlayerMainPlayback(player, hold))) {
            log.clear()
            main.pause()
            main.endPause(resume = true)
            main.pause()
            main.endPause(resume = false)

            assertThat(log).containsExactly("player.pause", "player.play", "player.pause")
        }
    }

    @Test
    fun `the way the pause went decides how it ends`() {
        val main = PlayerMainPlayback(player, hold)

        main.pause()
        hold.available = false
        main.endPause(resume = true)
        main.pause()
        hold.available = true
        main.endPause(resume = true)

        assertThat(log).containsExactly("hold", "end resume=true", "player.pause", "player.play")
    }
}
