package io.github.lorddetson.piplayer.playback

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** The renderers of the playback service (docs/playback.md §1.2). */
@OptIn(UnstableApi::class)
open class PlaybackRenderers(context: Context) : DefaultRenderersFactory(context) {

    /** Media3's audio renderer, built as Media3 1.11 builds it; PiPlayer has no extension decoders. */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out += AudioRenderer(
            context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            audioSink,
        )
    }

    /**
     * Sizes the decoder's input buffers for FLAC streams whose STREAMINFO leaves the frame size unknown (piped
     * encoders write it so): Media3 then keeps the platform's default of 32 KB and drops larger frames.
     */
    private class AudioRenderer(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink,
    ) : MediaCodecAudioRenderer(
        context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
        audioSink,
    ) {
        override fun getCodecMaxInputSize(
            codecInfo: MediaCodecInfo,
            format: Format,
            streamFormats: Array<out Format>,
        ): Int {
            val size = super.getCodecMaxInputSize(codecInfo, format, streamFormats)
            if (size != Format.NO_VALUE || format.sampleMimeType != MimeTypes.AUDIO_FLAC) return size
            return FlacFrames.maxFrameSize(format) ?: size
        }
    }
}
