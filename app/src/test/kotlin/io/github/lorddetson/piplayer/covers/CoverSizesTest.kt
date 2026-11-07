package io.github.lorddetson.piplayer.covers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The sizes covers load at (docs/library.md §13.3). */
class CoverSizesTest {

    @Test
    fun aSizeRoundsUpToTheNextStep() {
        assertThat(CoverSizes.step(1)).isEqualTo(96)
        assertThat(CoverSizes.step(96)).isEqualTo(96)
        assertThat(CoverSizes.step(97)).isEqualTo(128)
        assertThat(CoverSizes.step(168)).isEqualTo(192)
        assertThat(CoverSizes.step(520)).isEqualTo(768)
        assertThat(CoverSizes.step(1_080)).isEqualTo(1_536)
        assertThat(CoverSizes.step(5_000)).isEqualTo(2_048)
    }

    @Test
    fun theCachesKeepStepsUpTo768() {
        assertThat(CoverSizes.cached(768)).isTrue()
        assertThat(CoverSizes.cached(96)).isTrue()
        assertThat(CoverSizes.cached(1_024)).isFalse()
    }

    @Test
    fun theShorterSideComesToTheStep() {
        assertThat(CoverSizes.target(3_000, 3_000, 192)).isEqualTo(192 to 192)
        assertThat(CoverSizes.target(2_000, 1_000, 192)).isEqualTo(384 to 192)
        assertThat(CoverSizes.target(1_000, 3_001, 96)).isEqualTo(96 to 289)
    }

    @Test
    fun aPictureNoLargerKeepsItsSize() {
        assertThat(CoverSizes.target(64, 64, 192)).isEqualTo(64 to 64)
        assertThat(CoverSizes.target(1_000, 192, 192)).isEqualTo(1_000 to 192)
    }

    @Test
    fun aThinPictureKeepsAtLeastOnePixel() {
        assertThat(CoverSizes.target(100_000, 400, 96)).isEqualTo(24_000 to 96)
        assertThat(CoverSizes.target(1, 10_000, 96).first).isGreaterThanOrEqualTo(1)
    }
}
