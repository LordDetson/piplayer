package io.github.lorddetson.piplayer.ui.player

import android.content.res.Resources
import io.github.lorddetson.piplayer.R
import java.text.NumberFormat
import java.util.Locale

/** The format line of [summary] in the language of [resources], for example "FLAC · 24 bit · 96 kHz" (§3.5). */
fun formatLine(summary: FormatSummary, resources: Resources): String =
    AudioFormatField.entries.mapNotNull { it.value(summary, resources) }.joinToString(AUDIO_FORMAT_SEPARATOR)

internal const val AUDIO_FORMAT_SEPARATOR = " · "

internal enum class AudioFormatField(id: String, val label: Int) {
    CODEC("codec", R.string.details_field_codec),
    BIT_DEPTH("bit_depth", R.string.details_field_bit_depth),
    BITRATE("bitrate", R.string.details_field_bitrate),
    SAMPLE_RATE("sample_rate", R.string.details_field_sample_rate),
    CHANNELS("channels", R.string.details_field_channels),
    ;

    val canvasId = "now:audio_$id"

    fun value(summary: FormatSummary, resources: Resources, includeStereo: Boolean = false): String? = when (this) {
        CODEC -> summary.codec
        BIT_DEPTH -> summary.bits?.let {
            resources.getString(if (summary.floating) R.string.format_bits_float else R.string.format_bits, it)
        }
        BITRATE -> summary.kbps?.let { resources.getString(R.string.format_kbps, it) }
        SAMPLE_RATE -> summary.sampleRateHz?.let {
            resources.getString(R.string.format_khz, kilohertz(it, resources.configuration.locales[0]))
        }
        CHANNELS -> summary.channels?.let { channels(it, resources, includeStereo) }
    }
}

/** 44100 as "44.1" (or "44,1"), 11025 as "11.025". */
internal fun kilohertz(hertz: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).run {
    maximumFractionDigits = 3
    isGroupingUsed = false
    format(hertz / 1_000.0)
}

/** The compact format line normally omits stereo. The separate channel readout can display it. */
private fun channels(count: Int, resources: Resources, includeStereo: Boolean): String? = when (count) {
    1 -> resources.getString(R.string.format_mono)
    2 -> if (includeStereo) resources.getString(R.string.format_stereo) else null
    6 -> "5.1"
    8 -> "7.1"
    else -> resources.getQuantityString(R.plurals.format_channels, count, count)
}
