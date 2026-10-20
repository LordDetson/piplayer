package io.github.lorddetson.piplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor

/** The ExoPlayer of the playback service (docs/playback.md §1.2, §1.3); tests replace only its audio output. */
@OptIn(UnstableApi::class)
object PlaybackPlayer {

    fun builder(context: Context): ExoPlayer.Builder =
        ExoPlayer.Builder(context, PlaybackRenderers(context), DefaultMediaSourceFactory(context, extractors()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)

    /**
     * Raw AAC seeks by its average frame size and raw AMR in one mode by the size of its first frame; both need the
     * constant-bitrate flag, which changes nothing for MP3. An MP3 builds an index while it is read where its Xing
     * header has no table of contents or its length is unknown (docs/playback.md §1.3).
     */
    internal fun extractors(): ExtractorsFactory = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
}
