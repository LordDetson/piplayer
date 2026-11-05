package io.github.lorddetson.piplayer.tagging.contentkey

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.io.FileDescriptor

/**
 * Measures a file's length with the extractors of playback, without decoding and without reading sample data
 * (TI §3.1). Runs in `:tagger`, where a walk can be ended by killing the process, and shares nothing with the player.
 * The caller owns the descriptor, which no other extractor may use at the same time.
 */
@OptIn(UnstableApi::class)
object FrameWalk {

    private const val TAG = "FrameWalk"
    private const val US_PER_MS = 1_000L
    private const val FLAC_END_TOLERANCE_MS = 2_000L
    private const val MIME_FLAC = "audio/flac"

    /** The end of the last frame in milliseconds; null when no extractor reads the file. */
    fun walkMs(context: Context, fd: FileDescriptor): Long? = extracting(context, fd) { extractor, track ->
        walkToEnd(extractor, track)?.let(::toMs)
    }

    /**
     * [headerMs] from FLAC's STREAMINFO, unless the last frame, looked for near the end, ends more than 2 s before it;
     * then the length of what is left.
     */
    fun flacEndMs(context: Context, fd: FileDescriptor, headerMs: Long): Long? {
        val trusted = extracting(context, fd) { extractor, track ->
            val target = (headerMs - FLAC_END_TOLERANCE_MS).coerceAtLeast(0) * US_PER_MS
            extractor.seekTo(target, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            val endMs = walkToEnd(extractor, track)?.let(::toMs)
            endMs != null && endMs >= headerMs - FLAC_END_TOLERANCE_MS
        }
        return if (trusted == true) headerMs else walkMs(context, fd)
    }

    /**
     * Past FLAC's last frame the extractor hands out that frame again and again, with the same time, while advance()
     * keeps returning true (Media3 1.11.1, see MediaCodecPcmSource). Other formats may repeat a time, so only FLAC
     * ends there; walking does not read the samples, so a repeat of a large frame does no harm.
     */
    private fun walkToEnd(extractor: MediaExtractorCompat, track: Int): Long? {
        val endsAtRepeat = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) == MIME_FLAC
        val clock = SampleClock()
        var previous: Long? = null
        while (extractor.sampleTrackIndex >= 0) {
            val time = extractor.sampleTime
            if (endsAtRepeat && time == previous) break
            clock.observe(time)
            previous = time
            if (!extractor.advance()) break
        }
        return clock.endUs
    }

    private fun toMs(us: Long) = (us + US_PER_MS / 2) / US_PER_MS

    /** Runs [block] on an extractor over the first audio track; null when the file cannot be read that far. */
    private fun <T> extracting(context: Context, fd: FileDescriptor, block: (MediaExtractorCompat, Int) -> T): T? {
        val extractor = MediaExtractorCompat(context)
        return try {
            extractor.setDataSource(fd)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            block(extractor, track)
        } catch (e: Exception) {
            // A damaged file: the extractors throw IOException and sometimes less polite exceptions.
            Log.i(TAG, "The file could not be walked: $e")
            null
        } finally {
            extractor.release()
        }
    }
}
