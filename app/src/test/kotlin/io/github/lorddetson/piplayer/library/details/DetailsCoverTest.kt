package io.github.lorddetson.piplayer.library.details

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.covers.CoverSizes
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.covers.CaaCoverSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The details probe Cover Art Archive at their header's step (docs/library.md §18.3). */
class DetailsCoverTest {

    /** The header's cover in pixels, as Compose sizes the 96 dp `LibraryCover` of the header (§18.1). */
    private fun headerPx(density: Float) = with(Density(density)) { DetailsCover.HEADER_DP.dp.roundToPx() }

    @Test
    fun theProbeLoadsTheHeadersStepAndImageOnEveryCommonDensity() {
        // mdpi to xxxhdpi, with the densities of FHD+ phones (2.625, 2.75, 2.8125) between them.
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 2.8125f, 3f, 3.5f, 4f)) {
            val header = CoverSizes.step(headerPx(density))
            val probe = CoverSizes.step(DetailsCover.caaProbePx(density))

            assertThat(probe).describedAs("density $density").isEqualTo(header)
            assertThat(CaaCoverSource.sizeFor(probe)).describedAs("density $density").isEqualTo(CaaCoverSource.sizeFor(header))
        }
    }

    @Test
    fun theHeaderIs96Dp() {
        assertThat(DetailsCover.HEADER_DP).isEqualTo(96)
        assertThat(CaaCoverSource.sizeFor(CoverSizes.step(headerPx(3f)))).isEqualTo(CoverSize.DISPLAY)
    }
}
