package io.github.lorddetson.piplayer.playback

import android.view.KeyEvent
import io.github.lorddetson.piplayer.playback.StartIntentRules.Kind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StartIntentRulesTest {

    @Test
    fun `a media button intent with a key is recognised`() {
        assertThat(StartIntentRules.classify(StartIntentRules.MEDIA_BUTTON_ACTION, hasSelfUid = false))
            .isEqualTo(Kind.MEDIA_BUTTON)
    }

    @Test
    fun `a Media3 custom notification action is recognised`() {
        assertThat(StartIntentRules.classify(StartIntentRules.MEDIA3_CUSTOM_ACTION, hasSelfUid = false))
            .isEqualTo(Kind.CUSTOM_ACTION)
    }

    @Test
    fun `a Media3 self start has no action but its own UID extra`() {
        assertThat(StartIntentRules.classify(action = null, hasSelfUid = true)).isEqualTo(Kind.SELF_START)
    }

    @Test
    fun `anything else is foreign`() {
        for (action in listOf(null, "", "com.example.START", "android.intent.action.MAIN", "android.media.browse.MediaBrowserService")) {
            assertThat(StartIntentRules.classify(action, hasSelfUid = false)).describedAs(action).isEqualTo(Kind.FOREIGN)
        }
    }

    @Test
    fun `every media key Media3 handles is accepted`() {
        val handled = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STOP,
        )

        for (keyCode in handled) assertThat(StartIntentRules.isHandledMediaKey(keyCode)).describedAs("$keyCode").isTrue()
    }

    @Test
    fun `other keys are not media keys Media3 handles`() {
        for (keyCode in listOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_HEADSETHOOK + 1)) {
            assertThat(StartIntentRules.isHandledMediaKey(keyCode)).describedAs("$keyCode").isFalse()
        }
    }
}
