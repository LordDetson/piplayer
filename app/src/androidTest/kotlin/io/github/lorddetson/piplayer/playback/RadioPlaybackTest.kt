package io.github.lorddetson.piplayer.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioPlaybackTest {
    @Test
    fun radioAcceptsOnlySecureStreamLinks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(RadioPlayback.play(context, "Local file", "content://media/external/audio/media/5"))
        assertFalse(RadioPlayback.play(context, "Insecure", "http://example.org/radio.mp3"))
        assertFalse(RadioPlayback.play(context, "Credentials", "https://name:secret@example.org/radio.mp3"))
    }
}
