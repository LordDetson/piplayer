package io.github.lorddetson.piplayer.identification.fingerprint

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/** Why a file could not be decoded. */
sealed class DecodingException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    /** The container was recognised but holds no audio track. */
    class NoAudioTrack : DecodingException("The file has no audio track")

    /** No decoder on this device takes the audio format. */
    class UnsupportedFormat(val mimeType: String) : DecodingException("No decoder for $mimeType")

    /** The extractor or the decoder failed while reading. */
    class Failed(message: String, cause: Throwable?) : DecodingException(message, cause)
}

/**
 * Decodes the first audio track of a file (TI §3.2): Media3's `MediaExtractorCompat` parses the container, so the
 * extractors of playback also serve fingerprinting, and the platform `MediaCodec` decodes. The decoder is asked for
 * 16-bit output (what Chromaprint takes; on Android 13+ a float request would break the platform's trimming of MP3
 * and AAC encoder delay), but every PCM encoding it reports is accepted. PCM in the container (WAV) skips the decoder,
 * which would only pass the bytes on and could mislabel their encoding. The caller owns [fd]: it must stay open until
 * [decode] returns, is read through but not closed, and must not be used by another extractor at the same time.
 */
class MediaCodecPcmSource(private val context: Context, private val fd: FileDescriptor) : PcmSource {

    @OptIn(UnstableApi::class)
    override fun decode(consumer: (pcm: ByteBuffer, format: PcmFormat) -> Boolean): SourceInfo {
        val extractor = MediaExtractorCompat(context)
        try {
            try {
                extractor.setDataSource(fd)
            } catch (e: IOException) {
                throw DecodingException.Failed("The file could not be read: ${e.message}", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodingException.NoAudioTrack()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mimeType = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val durationUs = format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.takeIf { it > 0 }
            val samples = SampleReader(extractor, repeatsLastFrame = mimeType == MIME_FLAC, durationUs = durationUs)
            if (mimeType == MIME_RAW) {
                readPcm(samples, rawPcmFormat(format), consumer)
                return SourceInfo(mimeType, NO_DECODER, durationUs)
            }
            // The bitrate means nothing to a decoder, but the lookup checks it against the decoder's declared range: a
            // 320 kbit/s MP3 whose average the extractor works out at 320 044 bit/s would find no decoder at all.
            format.removeKey(MediaFormat.KEY_BIT_RATE)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: throw DecodingException.UnsupportedFormat(mimeType)
            // The extractor asks for 24-bit output for 24-bit FLAC; Chromaprint needs 16 bits only.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            // Without a size from the container the decoder's default input buffer may be smaller than a sample, for
            // example a FLAC frame of a file whose header leaves its frame sizes out.
            if (!format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_MAX_INPUT_SIZE)
            }

            val codec = try {
                MediaCodec.createByCodecName(name)
            } catch (e: IOException) {
                throw DecodingException.Failed("The decoder $name could not be created", e)
            }
            val endedEarly = try {
                codec.configure(format, null, null, 0)
                codec.start()
                pump(samples, codec, consumer)
            } catch (e: MediaCodec.CodecException) {
                throw DecodingException.Failed("The decoder $name failed: ${e.diagnosticInfo}", e)
            } finally {
                codec.release()
            }
            return SourceInfo(mimeType, name, durationUs, endedEarly)
        } finally {
            extractor.release()
        }
    }

    /**
     * Feeds samples to the decoder and hands its output on, until the end of the stream or the consumer stops. Every
     * free input buffer and every ready output buffer is taken without waiting; the loop waits briefly only when
     * neither side moved. A decoder error after some audio came out ends the stream there (a file cut off in the middle
     * of its last frame, for example) and is returned; an error before any audio fails the file.
     */
    private fun pump(samples: SampleReader, codec: MediaCodec, consumer: (ByteBuffer, PcmFormat) -> Boolean): String? {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var format: PcmFormat? = null
        var queued = 0
        var received = 0
        var inConsumer = false
        var idle = 0
        while (true) try {
            var progress = false
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                progress = true
                val size = samples.read(requireNotNull(codec.getInputBuffer(index)))
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, samples.timeUs, 0)
                    queued++
                }
            }
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (progress) 0 else TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progress = true
                    format = toPcmFormat(codec.outputFormat)
                    continue
                }
                if (index < 0) break
                progress = true
                received++
                val pcmFormat = format ?: toPcmFormat(codec.outputFormat).also { format = it }
                val keepGoing = try {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset).limit(info.offset + info.size)
                        inConsumer = true
                        consumer(buffer, pcmFormat).also { inConsumer = false }
                    } else {
                        true
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
                if (!keepGoing || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            }
            idle = if (progress) 0 else idle + 1
            if (idle > MAX_IDLE_POLLS) {
                throw DecodingException.Failed(
                    "The decoder stalled after $queued samples in and $received buffers out (input ended: $inputDone)",
                    null,
                )
            }
        } catch (e: IllegalStateException) {
            // A codec error arrives as CodecException, or (Android 11) as a plain IllegalStateException from the next
            // call. What the consumer throws (cancellation included) is not the decoder's and goes on as it is.
            if (inConsumer || received == 0) throw e
            val detail = (e as? MediaCodec.CodecException)?.diagnosticInfo ?: e.message
            return "The decoder failed after $received buffers: $detail"
        }
    }

    /** Hands the container's PCM on as it is, one sample (a block of frames) at a time. */
    private fun readPcm(samples: SampleReader, format: PcmFormat, consumer: (ByteBuffer, PcmFormat) -> Boolean) {
        val buffer = ByteBuffer.allocate(MAX_PCM_SAMPLE_SIZE)
        while (samples.read(buffer) >= 0) {
            if (!consumer(buffer, format)) return
        }
    }

    /**
     * Reads the selected track sample by sample; the stream ends where the extractor has no sample left. Media3
     * 1.11.1's extractor does not end a FLAC stream that way: past the last frame it hands out that frame again and
     * again, with the same time, while advance() keeps returning true, and reading such a repeat fails inside the
     * extractor once the frame is larger than 64 KiB. So when [repeatsLastFrame], a sample with the time of the one
     * before ends the stream before it is read, if it lies within the last seconds of [durationUs] (or the duration is
     * unknown). Other formats may legitimately repeat a time (laced Matroska frames) or step back (an MP3 whose sample
     * rate rises), and MP4 priming samples have negative times; all of those are read as they come.
     */
    @OptIn(UnstableApi::class)
    private class SampleReader(
        private val extractor: MediaExtractorCompat,
        private val repeatsLastFrame: Boolean,
        private val durationUs: Long?,
    ) {
        private var ended = false
        private var lastTimeUs: Long? = null

        /** Time of the sample [read] returned last. */
        var timeUs = 0L
            private set

        /** Reads the next sample into [buffer] (between its position and limit); -1 at the end of the stream. */
        fun read(buffer: ByteBuffer): Int {
            if (ended || extractor.sampleTrackIndex < 0) return end()
            val time = extractor.sampleTime
            val nearEnd = durationUs == null || time >= durationUs - END_WINDOW_US
            if (repeatsLastFrame && time == lastTimeUs && nearEnd) return end()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return end()
            lastTimeUs = time
            timeUs = time
            if (!extractor.advance()) ended = true
            return size
        }

        private fun end(): Int {
            ended = true
            return -1
        }

        private companion object {
            const val END_WINDOW_US = 2_000_000L
        }
    }

    /**
     * The format of PCM stored in the container. Media3 leaves the encoding key out for encodings Android has no
     * constant for (big-endian, 64-bit float); read with a default they would turn into noise, so they are refused.
     */
    private fun rawPcmFormat(format: MediaFormat): PcmFormat {
        if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            throw DecodingException.UnsupportedFormat("$MIME_RAW (big-endian or 64-bit samples)")
        }
        return toPcmFormat(format)
    }

    private fun toPcmFormat(format: MediaFormat): PcmFormat {
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        return PcmFormat(
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            encoding = PcmEncoding.fromAndroid(encoding)
                ?: throw DecodingException.Failed("The decoder put out encoding $encoding, which is not PCM", null),
        )
    }

    private companion object {
        const val MIME_RAW = "audio/raw"
        const val MIME_FLAC = "audio/flac"
        const val NO_DECODER = "none (PCM)"
        const val TIMEOUT_US = 10_000L

        /** Large enough for any FLAC frame of up to 8 channels of 24-bit audio in blocks of 32 768 samples. */
        const val DEFAULT_MAX_INPUT_SIZE = 1 shl 20

        /** WAV comes in pieces of a tenth of a second: up to 1.2 MB at 384 kHz with 8 channels of 32 bits. */
        const val MAX_PCM_SAMPLE_SIZE = 2 shl 20

        /** Waits in a row without any input taken or output given, about five seconds: the decoder is stuck. */
        const val MAX_IDLE_POLLS = 500
    }
}
