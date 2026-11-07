package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** The placeholder's colors (docs/library.md §13.4). */
class PlaceholderColorsTest {

    private val names = (0 until 2_000).map { "Album $it" } + listOf("", " ", "Кино", "Группа крови", "東京", "AC/DC", "ÆØÅ")

    @Test
    fun theSameNameGivesTheSameColors() {
        assertThat(PlaceholderColors.of("Abbey Road", dark = false)).isEqualTo(PlaceholderColors.of("Abbey Road", dark = false))
        assertThat(PlaceholderColors.of("Abbey Road", dark = true)).isEqualTo(PlaceholderColors.of("Abbey Road", dark = true))
        // A stable hash, not String.hashCode of a given JVM: this value holds on every device.
        assertThat(PlaceholderColors.hue("Abbey Road")).isEqualTo(PlaceholderColors.hue("abbey road"))
    }

    @Test
    fun theHueIsFnv1aOfTheLowercaseName() {
        // FNV-1a (32 bit) of "a" is 0xE40C292C.
        assertThat(PlaceholderColors.hue("A")).isCloseTo((0xE40C292CL % 360).toFloat(), within(0.001f))
    }

    @Test
    fun theNoteStandsOutInBothThemes() {
        for (name in names) {
            for (dark in listOf(false, true)) {
                val colors = PlaceholderColors.of(name, dark)
                assertThat(PlaceholderColors.contrast(colors.background, colors.note)).describedAs("$name dark=$dark").isGreaterThanOrEqualTo(3.0)
            }
        }
    }

    @Test
    fun theBackgroundIsLightInTheLightThemeAndDarkInTheDarkOne() {
        for (name in names) {
            assertThat(PlaceholderColors.luminance(PlaceholderColors.of(name, dark = false).background)).describedAs(name).isGreaterThan(0.45)
            assertThat(PlaceholderColors.luminance(PlaceholderColors.of(name, dark = true).background)).describedAs(name).isLessThan(0.1)
        }
    }

    @Test
    fun theColorsAreOpaqueAndCalm() {
        for (name in names) {
            val color = PlaceholderColors.of(name, dark = false).background
            assertThat(color ushr 24).isEqualTo(0xFF)
            val r = color shr 16 and 0xFF
            val g = color shr 8 and 0xFF
            val b = color and 0xFF
            // Low saturation: the channels stay near each other.
            assertThat(maxOf(r, g, b) - minOf(r, g, b)).describedAs(name).isLessThan(100)
        }
    }

    @Test
    fun namesSpreadOverTheHues() {
        val sixths = names.map { (PlaceholderColors.hue(it) / 60).toInt() }.groupingBy { it }.eachCount()

        assertThat(sixths.keys).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5)
        assertThat(sixths.values.min()).isGreaterThan(names.size / 12)
    }

    @Test
    fun contrastFollowsWcag() {
        assertThat(PlaceholderColors.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isCloseTo(21.0, within(0.01))
        assertThat(PlaceholderColors.contrast(0xFF777777.toInt(), 0xFF777777.toInt())).isCloseTo(1.0, within(0.001))
    }
}
