package io.github.lorddetson.piplayer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.settings.UiGestureAction
import io.github.lorddetson.piplayer.settings.UiGestureActionId
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class GesturePlaybackIntegrationTest {
    @Test
    fun volumeAndSpeedChangeOnTheActualPlayer() {
        onMain {
            val player = ExoPlayer.Builder(TestFiles.targetContext).build()
            try {
                player.volume = 0.5f
                assertThat(performPlaybackGesture(UiGestureAction(UiGestureActionId.VOLUME_UP), player)).isTrue()
                assertThat(player.volume).isEqualTo(0.6f)
                assertThat(performPlaybackGesture(UiGestureAction(UiGestureActionId.SPEED_DOWN), player)).isTrue()
                assertThat(player.playbackParameters.speed).isEqualTo(0.9f)
                assertThat(player.playbackParameters.pitch).isEqualTo(1f)
                assertThat(performPlaybackGesture(UiGestureAction(UiGestureActionId.SPEED_RESET), player)).isTrue()
                assertThat(player.playbackParameters.speed).isEqualTo(1f)
            } finally {
                player.release()
            }
        }
    }
}
