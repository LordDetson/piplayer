package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import io.github.lorddetson.piplayer.tagging.write.playbackTagWriteGate
import java.io.Closeable
import java.io.InterruptedIOException

/** The ExoPlayer of the playback service (docs/playback.md §1.2, §1.3); tests replace only its audio output. */
@OptIn(UnstableApi::class)
object PlaybackPlayer {

    /** Previous goes back to the start of the song after this much of it, otherwise to the previous song (§3.6). */
    const val MAX_SEEK_TO_PREVIOUS_MS = 3_000L

    /**
     * The service's player; the snippet player (TI §6) is built the same way with [handleAudioFocus] off, because it
     * requests its own transient focus.
     */
    fun builder(context: Context, handleAudioFocus: Boolean = true): ExoPlayer.Builder =
        ExoPlayer.Builder(context, PlaybackRenderers(context), mediaSources(context))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMaxSeekToPreviousPositionMs(MAX_SEEK_TO_PREVIOUS_MS)

    /**
     * Every item is a local file, so each goes straight to a progressive source: the default factory would first work
     * out the kind of stream of each of up to 20 000 items on the main thread (docs/playback.md §2.3).
     */
    internal fun mediaSources(context: Context): MediaSource.Factory {
        val upstream = DefaultDataSource.Factory(context)
        return ProgressiveMediaSource.Factory({ PlaybackTagDataSource(upstream.createDataSource()) }, extractors())
    }

    /**
     * Raw AAC seeks by its average frame size and raw AMR in one mode by the size of its first frame; both need the
     * constant-bitrate flag, which changes nothing for MP3. An MP3 builds an index while it is read where its Xing
     * header has no table of contents or its length is unknown (docs/playback.md §1.3).
     */
    internal fun extractors(): ExtractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
}

@OptIn(UnstableApi::class)
private class PlaybackTagDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var reading: Closeable? = null

    override fun open(dataSpec: DataSpec): Long {
        val lease = try {
            playbackTagWriteGate.beginRead(dataSpec.uri.toString())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for a file tag write").apply { initCause(error) }
        }
        reading = lease
        return try {
            upstream.open(dataSpec)
        } catch (error: Exception) {
            lease.close()
            reading = null
            throw error
        }
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            reading?.close()
            reading = null
        }
    }
}
