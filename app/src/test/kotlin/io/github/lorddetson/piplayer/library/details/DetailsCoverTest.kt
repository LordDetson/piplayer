package io.github.lorddetson.piplayer.library.details

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.covers.CoverSizes
import io.github.lorddetson.piplayer.identification.caa.CoverSize
import io.github.lorddetson.piplayer.covers.CaaCoverSource
import io.github.lorddetson.piplayer.covers.CoverRelease
import io.github.lorddetson.piplayer.covers.CoverStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    private val step = CoverStep.Caa(CoverRelease("4b9a8c3e-1f0d-4c2a-9e7b-5d6f8a1b2c3d"))

    @Test
    fun theProbeAsksTheLoaderAtTheHeadersPixelsOnTheScreensDensity() = runTest {
        for (density in listOf(1f, 2f, 2.75f, 3f, 4f)) {
            val asked = mutableListOf<Pair<CoverStep.Caa, Int>>()
            val probe = DetailsCaaProbe({ density }, backgroundScope) { step, px -> asked += step to px; true }

            assertThat(probe.online(step)).describedAs("density $density").isTrue()
            assertThat(asked).describedAs("density $density").containsExactly(step to headerPx(density))
        }
    }

    @Test
    fun aStepWithoutAPictureIsNotOnline() = runTest {
        val probe = DetailsCaaProbe({ 3f }, backgroundScope) { _, _ -> false }

        assertThat(probe.online(step)).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theDetailsWaitThreeSecondsAndTheLoadGoesOnInTheBackground() = runTest {
        var loaded = false
        val probe = DetailsCaaProbe({ 3f }, backgroundScope) { _, _ -> delay(5_000); loaded = true; true }

        assertThat(probe.online(step)).isFalse()
        assertThat(currentTime).isEqualTo(3_000)
        assertThat(loaded).isFalse()

        // The load outlives the read given up: it finishes at 5 s in the background scope.
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(loaded).isTrue()
    }
}
