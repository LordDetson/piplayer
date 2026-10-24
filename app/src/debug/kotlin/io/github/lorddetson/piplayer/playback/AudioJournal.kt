package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.audio.AudioSink
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Debug builds only: a journal of the audio path, to check gapless playback with the screen off (docs/playback.md
 * §8.3). The decoders, each new audio output, song changes, pauses, stalls and underruns go to
 * files/debug/audio-journal.txt, a line each (read it with
 * `adb shell run-as io.github.lorddetson.piplayer cat files/debug/audio-journal.txt`), and to [events] for the tests.
 * Past 512 KB the file starts again, keeping the one before as audio-journal.txt.old.
 */
@OptIn(UnstableApi::class)
object AudioJournal {

    enum class Kind { DECODER, OUTPUT, OUTPUT_RELEASED, SONG, UNDERRUN, PLAY, PAUSE, BUFFERING, READY, ENDED }

    /** What happened while [song] (1 for the first of the queue) was the current one. */
    data class Event(val kind: Kind, val song: Int, val detail: String)

    private const val TAG = "AudioJournal"
    private const val LIMIT = 512 * 1024

    private val recorded = CopyOnWriteArrayList<Event>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "audio-journal").apply { isDaemon = true } }

    /** Everything since the process started or the last [reset]. */
    val events: List<Event> get() = recorded.toList()

    fun reset() = recorded.clear()

    fun file(context: Context) = File(context.filesDir, "debug/audio-journal.txt")

    fun attach(context: Context, player: ExoPlayer) {
        player.addAnalyticsListener(Listener(file(context.applicationContext)))
    }

    private class Listener(private val file: File) : AnalyticsListener {

        override fun onAudioDecoderInitialized(
            eventTime: EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = log(eventTime, Kind.DECODER, decoderName, "decoder $decoderName")

        override fun onAudioTrackInitialized(eventTime: EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val config = audioTrackConfig
            val channels = Integer.bitCount(config.channelConfig)
            log(
                eventTime,
                Kind.OUTPUT,
                "${config.sampleRate} Hz",
                "output ${config.sampleRate} Hz, $channels channels, encoding ${config.encoding}, " +
                    "buffer ${config.bufferSize} bytes" + (if (config.offload) ", offload" else ""),
            )
        }

        override fun onAudioTrackReleased(eventTime: EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) =
            log(eventTime, Kind.OUTPUT_RELEASED, "", "output released")

        override fun onMediaItemTransition(eventTime: EventTime, mediaItem: MediaItem?, reason: Int) {
            val why = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "automatic"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                else -> "new queue"
            }
            val song = eventTime.currentWindowIndex + 1
            log(eventTime, Kind.SONG, why, "song $song of ${eventTime.timeline.windowCount}, $why")
        }

        override fun onAudioUnderrun(
            eventTime: EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) = log(
            eventTime,
            Kind.UNDERRUN,
            "$elapsedSinceLastFeedMs ms",
            "underrun, buffer $bufferSize bytes ($bufferSizeMs ms), $elapsedSinceLastFeedMs ms since the last feed",
        )

        override fun onPlayWhenReadyChanged(eventTime: EventTime, playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                log(eventTime, Kind.PLAY, "", "play")
            } else {
                val why = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "requested"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becoming noisy"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of the song"
                    else -> "reason $reason"
                }
                log(eventTime, Kind.PAUSE, why, "pause, $why")
            }
        }

        override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> log(eventTime, Kind.BUFFERING, "", "buffering")
                Player.STATE_READY -> log(eventTime, Kind.READY, "", "ready")
                Player.STATE_ENDED -> log(eventTime, Kind.ENDED, "", "ended")
            }
        }

        private fun log(eventTime: EventTime, kind: Kind, detail: String, line: String) {
            val song = eventTime.currentWindowIndex + 1
            recorded += Event(kind, song, detail)
            val text = if (kind == Kind.SONG) line else "$line, in song $song"
            val stamped = "${Instant.now()} $text\n"
            writer.execute { append(stamped) }
        }

        private fun append(line: String) {
            try {
                file.parentFile?.mkdirs()
                if (file.length() > LIMIT) {
                    val old = File(file.path + ".old")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line)
            } catch (e: IOException) {
                Log.w(TAG, "Could not write the audio journal", e)
            }
        }
    }
}
