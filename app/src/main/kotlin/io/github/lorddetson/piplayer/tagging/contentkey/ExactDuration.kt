package io.github.lorddetson.piplayer.tagging.contentkey

import io.github.lorddetson.piplayer.nativelib.taglib.MpegLayout
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** What is left to do after the tag pass read a file's header (TI §3.1). */
@Serializable
enum class DurationCheck {
    /** The header value is final. */
    NONE,

    /** The header value holds unless the file's last frame, found by the extractor, ends more than 2 s before it. */
    FLAC_END,

    /** Only a walk over all frames gives the length. */
    WALK,
}

/** [headerMs] is the value to store for now, null when the header says nothing reliable. */
@Serializable
data class DurationPlan(val headerMs: Long?, val check: DurationCheck)

/** The rules of TI §3.1 for trusting a header. */
object DurationRules {

    private const val BYTE_COUNT_TOLERANCE = 0.02
    private const val CBR_TOLERANCE_MS = 2_000L
    private const val MS_PER_SECOND = 1_000L
    private val WALK = DurationPlan(null, DurationCheck.WALK)

    /** [taglibMs] is TagLib's length, null or 0 when it has none. */
    fun plan(container: AudioContainer, mpeg: MpegLayout?, taglibMs: Int?, located: Located): DurationPlan {
        val header = taglibMs?.takeIf { it > 0 }?.toLong()
        return when (container) {
            AudioContainer.MPEG -> mpeg?.let { mpegPlan(it, header) } ?: WALK
            AudioContainer.FLAC -> if (header != null) DurationPlan(header, DurationCheck.FLAC_END) else WALK
            AudioContainer.MP4, AudioContainer.WAV -> if (located.declaredBeyondEnd) WALK else final(header)
            AudioContainer.OGG ->
                located.ogg?.let(::oggMs)?.let { DurationPlan(it, DurationCheck.NONE) } ?: final(header)
            AudioContainer.OTHER -> final(header)
        }
    }

    private fun final(header: Long?) = if (header != null) DurationPlan(header, DurationCheck.NONE) else WALK

    private fun mpegPlan(layout: MpegLayout, header: Long?): DurationPlan {
        val count = layout.frameCount
        if (layout.adts || count == null || header == null) return WALK
        val withServiceFrame = minOf(layout.lastFrame + layout.lastFrameLength, layout.audioEnd) - layout.firstFrame
        val payload = withServiceFrame - if (layout.serviceFrame) layout.firstFrameLength else 0
        if (!near(count.bytes, withServiceFrame) && !near(count.bytes, payload)) return WALK
        if (count.constantBitrate) {
            if (layout.firstFrameBitrateKbps <= 0) return WALK
            val fromPayload = payload * Byte.SIZE_BITS / layout.firstFrameBitrateKbps
            if (abs(header - fromPayload) > CBR_TOLERANCE_MS) return WALK
        }
        return DurationPlan(header, DurationCheck.NONE)
    }

    private fun near(declared: Long, actual: Long) =
        actual > 0 && abs(declared - actual) <= actual * BYTE_COUNT_TOLERANCE

    /** The length in milliseconds, null when the numbers make no sense. */
    private fun oggMs(length: OggLength): Long? {
        val samples = length.finalGranule - length.preSkip
        if (samples <= 0 || length.sampleRate <= 0) return null
        val rate = length.sampleRate.toLong()
        val seconds = samples / rate
        if (seconds > Long.MAX_VALUE / MS_PER_SECOND - 1) return null
        return seconds * MS_PER_SECOND + (samples % rate * MS_PER_SECOND + rate / 2) / rate
    }
}

/** The end of a stream from the times of its samples: the last time plus the length of a frame. */
class SampleClock {

    private var last: Long? = null
    private var frameUs = 0L

    /** Takes the time of the next sample; a repeated time (some extractors repeat a last FLAC frame) changes nothing. */
    fun observe(timeUs: Long) {
        val previous = last
        if (previous == null) {
            last = timeUs
        } else if (timeUs > previous) {
            frameUs = timeUs - previous
            last = timeUs
        }
    }

    /** The end of the last sample so far, null before the first. */
    val endUs: Long? get() = last?.plus(frameUs)
}
