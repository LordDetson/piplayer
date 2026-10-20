package io.github.lorddetson.piplayer.playback

import android.content.res.Configuration
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.player.FormatSummary
import io.github.lorddetson.piplayer.ui.player.formatLine
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The format line of real files, from the tracks a controller sees (docs/playback.md §3.5). */
@RunWith(AndroidJUnit4::class)
class FormatLineFixturesTest {

    private lateinit var controller: MediaController

    private val english = TestFiles.targetContext.let { context ->
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.US) }
        context.createConfigurationContext(configuration).resources
    }

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    private fun lineOf(asset: String): String? {
        controller.play(asset)
        controller.waitUntil("the tracks of $asset are known") { currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) }
        val format = onMain {
            val group = controller.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            group.getTrackFormat((0 until group.length).first(group::isTrackSelected))
        }
        return FormatSummary.of(format)?.let { formatLine(it, english) }
    }

    @Test
    fun everyDecodingFixtureHasItsLine() {
        val expected = mapOf(
            "decoding/flac-96000-24bit-stereo.flac" to "FLAC · 24 bit · 96 kHz",
            "decoding/flac-48000-16bit-5.1.flac" to "FLAC · 16 bit · 48 kHz · 5.1",
            // 320 and 128 kbit/s files of 2 s: their audio includes the encoder's delay and padding (about 25 ms),
            // which Media3's average over the playable length counts in; a song of minutes rounds to 320 and 128.
            "decoding/mp3-44100-320k-stereo.mp3" to "MP3 · 326 kbit/s · 44.1 kHz",
            "decoding/mp3-44100-stereo.mp3" to "MP3 · 130 kbit/s · 44.1 kHz",
            "decoding/aac-44100-stereo.m4a" to "AAC · 128 kbit/s · 44.1 kHz",
            "decoding/opus-48000-stereo.opus" to "Opus · 48 kHz",
            "decoding/vorbis-48000-mono.ogg" to "Vorbis · 86 kbit/s · 48 kHz · mono",
            "decoding/wav-48000-24bit-stereo.wav" to "WAV · 24 bit · 48 kHz",
            "decoding/wav-44100-float-mono.wav" to "WAV · 32 bit float · 44.1 kHz · mono",
            "decoding/wav-44100-double-mono.wav" to "WAV · 64 bit float · 44.1 kHz · mono",
        )

        val actual = expected.keys.associateWith(::lineOf)

        assertThat(actual).isEqualTo(expected)
    }
}
