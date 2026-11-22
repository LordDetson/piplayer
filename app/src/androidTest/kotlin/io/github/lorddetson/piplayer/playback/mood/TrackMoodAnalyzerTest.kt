package io.github.lorddetson.piplayer.playback.mood

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.ui.player.rememberMoodBackdrop
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TrackMoodAnalyzerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun readsTempoAndIntensityFromLocalPcm() {
        val context = TestFiles.targetContext
        val audio = File.createTempFile("track-mood", ".wav", context.cacheDir)
        try {
            writePulseTrack(audio)
            val mood = runBlocking { TrackMoodAnalyzer.analyze(context, Uri.fromFile(audio)) }
            assertThat(mood?.tempoBpm).isBetween(121, 127)
            assertThat(mood?.intensity).isBetween(0.5f, 0.9f)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun skipsNetworkTracks() {
        val mood = runBlocking {
            TrackMoodAnalyzer.analyze(TestFiles.targetContext, Uri.parse("https://example.invalid/music.wav"))
        }
        assertThat(mood).isNull()
    }

    @Test
    fun aLocalTrackTintsTheBackdropAndDisablingItRestoresTheThemeSurface() {
        val audio = File.createTempFile("mood-backdrop", ".wav", TestFiles.targetContext.cacheDir)
        try {
            writePulseTrack(audio)
            val item = MediaItem.fromUri(Uri.fromFile(audio))
            val enabled = mutableStateOf(false)
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme(surface = Color(0xFF151515))) {
                    Box(Modifier.size(160.dp).then(rememberMoodBackdrop(item, enabled.value)).testTag("mood_backdrop"))
                }
            }
            fun backgroundPixel(): Color {
                val pixels = compose.onNodeWithTag("mood_backdrop").captureToImage().toPixelMap()
                return pixels[pixels.width / 2, pixels.height / 4]
            }
            val surface = backgroundPixel()
            compose.runOnIdle { enabled.value = true }
            compose.waitUntil(10_000) { backgroundPixel() != surface }
            compose.runOnIdle { enabled.value = false }
            assertThat(backgroundPixel()).isEqualTo(surface)
        } finally {
            audio.delete()
        }
    }
}

private fun writePulseTrack(file: File) {
    val sampleRate = 16_000
    val frames = sampleRate * 10
    val bytes = frames * Short.SIZE_BYTES
    val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".encodeToByteArray()).putInt(36 + bytes).put("WAVE".encodeToByteArray())
    buffer.put("fmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
    buffer.putInt(sampleRate * Short.SIZE_BYTES).putShort(2).putShort(16)
    buffer.put("data".encodeToByteArray()).putInt(bytes)
    for (frame in 0 until frames) {
        val time = frame.toDouble() / sampleRate
        val phase = (time * 124 / 60) % 1
        val envelope = 0.08 + 0.92 * exp(-phase * 22)
        buffer.putShort((Short.MAX_VALUE * 0.75 * envelope * sin(2 * PI * time * 100)).toInt().toShort())
    }
    file.writeBytes(buffer.array())
}
