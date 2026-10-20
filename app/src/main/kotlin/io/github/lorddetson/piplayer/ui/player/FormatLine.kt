package io.github.lorddetson.piplayer.ui.player

import android.content.res.Resources
import io.github.lorddetson.piplayer.R
import java.text.NumberFormat
import java.util.Locale

/** The format line of [summary] in the language of [resources], for example "FLAC · 24 bit · 96 kHz" (§3.5). */
fun formatLine(summary: FormatSummary, resources: Resources): String {
    val locale = resources.configuration.locales[0]
    return buildList {
        add(summary.codec)
        summary.bits?.let { add(resources.getString(if (summary.floating) R.string.format_bits_float else R.string.format_bits, it)) }
        summary.kbps?.let { add(resources.getString(R.string.format_kbps, it)) }
        summary.sampleRateHz?.let { add(resources.getString(R.string.format_khz, kilohertz(it, locale))) }
        summary.channels?.let { channels(it, resources) }?.let(::add)
    }.joinToString(" · ")
}

/** 44100 as "44.1" (or "44,1"), 11025 as "11.025". */
private fun kilohertz(hertz: Int, locale: Locale): String = NumberFormat.getNumberInstance(locale).run {
    maximumFractionDigits = 3
    isGroupingUsed = false
    format(hertz / 1_000.0)
}

/** The layout when it is not stereo. */
private fun channels(count: Int, resources: Resources): String? = when (count) {
    1 -> resources.getString(R.string.format_mono)
    2 -> null
    6 -> "5.1"
    8 -> "7.1"
    else -> resources.getQuantityString(R.plurals.format_channels, count, count)
}
