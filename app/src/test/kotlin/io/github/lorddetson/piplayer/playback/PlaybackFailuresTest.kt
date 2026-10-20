package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlaybackFailuresTest {

    @Test
    fun `errors of the file move on to the next song`() {
        val songErrors = listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            // A check that fails on the playback thread is most likely tripped by an odd file.
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        )

        for (code in songErrors) assertThat(PlaybackFailures.kind(code)).describedAs("$code").isEqualTo(FailureKind.SONG)
    }

    @Test
    fun `errors of the device stop playback`() {
        val deviceErrors = listOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )

        for (code in deviceErrors) {
            assertThat(PlaybackFailures.kind(code)).describedAs("$code").isEqualTo(FailureKind.DEVICE)
        }
    }

    @Test
    fun `an audio output the file's format cannot be set up for is the song's problem`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(9).build()
        val configuration = AudioSink.ConfigurationException("Invalid output channel config", format)
        val rejected = AudioSink.InitializationException("Rejected", 0, format, false, IllegalArgumentException())

        for (cause in listOf<Throwable>(configuration, RuntimeException(configuration), rejected)) {
            assertThat(PlaybackFailures.kind(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, cause))
                .describedAs("$cause").isEqualTo(FailureKind.SONG)
        }
    }

    @Test
    fun `an audio output that fails to start otherwise is the device's problem`() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(2).build()
        val failed = AudioSink.InitializationException("No output", 0, format, false, null)

        for (cause in listOf(failed, null)) {
            assertThat(PlaybackFailures.kind(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, cause))
                .describedAs("$cause").isEqualTo(FailureKind.DEVICE)
        }
    }

    @Test
    fun `playback moves on until five songs failed in a row`() {
        val streak = FailureStreak()

        val decisions = List(5) { streak.failed(queueSize = 100) }

        assertThat(decisions).containsExactly(true, true, true, true, false)
        assertThat(streak.count).isEqualTo(5)
    }

    @Test
    fun `a song that opens ends the streak`() {
        val streak = FailureStreak()
        repeat(4) { streak.failed(queueSize = 100) }

        streak.opened()

        assertThat(streak.count).isZero()
        assertThat(List(4) { streak.failed(queueSize = 100) }).containsOnly(true)
    }

    @Test
    fun `a short queue stops once each of its songs failed`() {
        val streak = FailureStreak()

        assertThat(listOf(streak.failed(queueSize = 2), streak.failed(queueSize = 2))).containsExactly(true, false)
    }
}
