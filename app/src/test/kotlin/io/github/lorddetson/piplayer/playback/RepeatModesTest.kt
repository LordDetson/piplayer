package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RepeatModesTest {

    @Test
    fun `repeat goes off, all, one and round again`() {
        assertThat(RepeatModes.next(Player.REPEAT_MODE_OFF)).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(RepeatModes.next(Player.REPEAT_MODE_ALL)).isEqualTo(Player.REPEAT_MODE_ONE)
        assertThat(RepeatModes.next(Player.REPEAT_MODE_ONE)).isEqualTo(Player.REPEAT_MODE_OFF)
        assertThat(RepeatModes.CYCLE).containsExactly(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)
    }
}
