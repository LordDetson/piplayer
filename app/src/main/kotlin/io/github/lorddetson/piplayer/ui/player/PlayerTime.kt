package io.github.lorddetson.piplayer.ui.player

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.media3.common.C
import io.github.lorddetson.piplayer.ui.formatDuration
import java.util.Locale

/** Times on Now Playing (docs/playback.md §3.3, §3.8). */
object PlayerTime {

    /** The elapsed time, "m:ss". */
    fun elapsed(positionMs: Long): String = formatDuration(positionMs.coerceAtLeast(0))

    /**
     * The remaining time, "-m:ss", or "--:--" when the length is unknown. Counted in whole seconds, so that the
     * elapsed and the remaining time always add up to the length.
     */
    fun remaining(positionMs: Long, durationMs: Long): String {
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return formatDuration(null)
        val seconds = (durationMs / 1_000 - positionMs.coerceAtLeast(0) / 1_000).coerceAtLeast(0)
        return "-" + formatDuration(seconds * 1_000)
    }

    /** A time left or a timer's length as "h:mm", without seconds: a part of a minute counts as a whole one. */
    fun clock(durationMs: Long): String {
        val minutes = wholeMinutes(durationMs) / 60_000
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }

    /** [durationMs] rounded up to whole minutes, as the sleep timer shows and states it. */
    fun wholeMinutes(durationMs: Long): Long = (durationMs.coerceAtLeast(0) + 59_999) / 60_000 * 60_000

    /** [durationMs] in words, as accessibility services read it: "1 minute, 2 seconds". */
    fun spoken(durationMs: Long, locale: Locale): String {
        val seconds = durationMs.coerceAtLeast(0) / 1_000
        val measures = listOf(
            Measure(seconds / 3_600, MeasureUnit.HOUR),
            Measure(seconds / 60 % 60, MeasureUnit.MINUTE),
            Measure(seconds % 60, MeasureUnit.SECOND),
        ).filter { it.number.toLong() > 0 }.ifEmpty { listOf(Measure(0, MeasureUnit.SECOND)) }
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
    }
}
