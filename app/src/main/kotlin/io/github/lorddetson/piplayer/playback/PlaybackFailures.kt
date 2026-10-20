package io.github.lorddetson.piplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/** Whether a playback error belongs to the song or to the device (docs/playback.md §2.4). */
enum class FailureKind { SONG, DEVICE }

@OptIn(UnstableApi::class)
object PlaybackFailures {

    /** Errors of the audio output, of the decoder resources or of the player's progress, which another song would hit too. */
    private val deviceErrors = setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )

    /**
     * The kind of a [PlaybackException] with [errorCode] and [cause]; anything not known to be the device's counts as the
     * song's. The audio output reports a format of the file it cannot be set up for under the same code as an output
     * that fails to start, so the cause tells them apart: a song with 9 channels is skipped, a dead output stops.
     */
    fun kind(errorCode: Int, cause: Throwable? = null): FailureKind = when {
        errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && rejectsTheFormat(cause) -> FailureKind.SONG
        errorCode in deviceErrors -> FailureKind.DEVICE
        else -> FailureKind.SONG
    }

    private fun rejectsTheFormat(cause: Throwable?): Boolean = generateSequence(cause) { it.cause }.any {
        it is AudioSink.ConfigurationException ||
            (it is AudioSink.InitializationException && it.cause is IllegalArgumentException)
    }
}

/** Counts songs that failed in a row (docs/playback.md §2.4). */
class FailureStreak(private val limit: Int = LIMIT) {

    var count = 0
        private set

    /** A song opened: the streak is over. */
    fun opened() {
        count = 0
    }

    /**
     * A song of a queue of [queueSize] songs failed; true to move on to the next one, false to stop: after [limit] in a
     * row, or once each song of a shorter queue failed (with repeat, a queue of broken songs would go round for ever).
     */
    fun failed(queueSize: Int): Boolean {
        count++
        return count < minOf(limit, queueSize)
    }

    companion object {
        const val LIMIT = 5
    }
}
