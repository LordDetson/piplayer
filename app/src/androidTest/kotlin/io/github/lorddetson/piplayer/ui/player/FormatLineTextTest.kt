package io.github.lorddetson.piplayer.ui.player

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The words and numbers of the format line in each language (§3.5). */
@RunWith(AndroidJUnit4::class)
class FormatLineTextTest {

    private fun resources(language: String): Resources {
        val context = TestFiles.targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration).resources
    }

    private val english = resources("en-US")
    private val russian = resources("ru-RU")

    @Test
    fun partsAreJoinedInTheirOrder() {
        assertThat(formatLine(FormatSummary("FLAC", bits = 24, sampleRateHz = 96_000, channels = 2), english))
            .isEqualTo("FLAC · 24 bit · 96 kHz")
        assertThat(formatLine(FormatSummary("MP3", kbps = 320, sampleRateHz = 44_100, channels = 2), english))
            .isEqualTo("MP3 · 320 kbit/s · 44.1 kHz")
        assertThat(formatLine(FormatSummary("WAV", bits = 32, floating = true, sampleRateHz = 44_100, channels = 1), english))
            .isEqualTo("WAV · 32 bit float · 44.1 kHz · mono")
        assertThat(formatLine(FormatSummary("Opus", sampleRateHz = 48_000, channels = 2), english)).isEqualTo("Opus · 48 kHz")
        assertThat(formatLine(FormatSummary("MP3"), english)).isEqualTo("MP3")
    }

    @Test
    fun sampleRatesKeepTheirFractions() {
        assertThat(formatLine(FormatSummary("AAC", sampleRateHz = 22_050), english)).isEqualTo("AAC · 22.05 kHz")
        assertThat(formatLine(FormatSummary("WAV", sampleRateHz = 11_025), english)).isEqualTo("WAV · 11.025 kHz")
    }

    @Test
    fun layoutsOtherThanStereoAreNamed() {
        fun line(channels: Int) = formatLine(FormatSummary("FLAC", bits = 16, sampleRateHz = 48_000, channels = channels), english)

        assertThat(line(1)).isEqualTo("FLAC · 16 bit · 48 kHz · mono")
        assertThat(line(2)).isEqualTo("FLAC · 16 bit · 48 kHz")
        assertThat(line(3)).isEqualTo("FLAC · 16 bit · 48 kHz · 3 channels")
        assertThat(line(6)).isEqualTo("FLAC · 16 bit · 48 kHz · 5.1")
        assertThat(line(8)).isEqualTo("FLAC · 16 bit · 48 kHz · 7.1")
    }

    @Test
    fun russianHasItsOwnUnitsAndDecimalComma() {
        assertThat(formatLine(FormatSummary("FLAC", bits = 24, sampleRateHz = 96_000, channels = 2), russian))
            .isEqualTo("FLAC · 24 бит · 96 кГц")
        assertThat(formatLine(FormatSummary("MP3", kbps = 320, sampleRateHz = 44_100, channels = 1), russian))
            .isEqualTo("MP3 · 320 кбит/с · 44,1 кГц · моно")
        assertThat(formatLine(FormatSummary("WAV", bits = 16, sampleRateHz = 44_100, channels = 3), russian))
            .isEqualTo("WAV · 16 бит · 44,1 кГц · 3 канала")
    }
}
