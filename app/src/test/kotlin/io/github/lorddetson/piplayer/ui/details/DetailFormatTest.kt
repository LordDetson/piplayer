package io.github.lorddetson.piplayer.ui.details

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Lengths in track details, to the millisecond (docs/library.md §18.2). */
class DetailFormatTest {

    @Test
    fun `minutes, seconds and milliseconds`() {
        assertThat(DetailFormat.length(205_412)).isEqualTo("3:25.412")
        assertThat(DetailFormat.length(5_007)).isEqualTo("0:05.007")
        assertThat(DetailFormat.length(0)).isEqualTo("0:00.000")
        assertThat(DetailFormat.length(59_999)).isEqualTo("0:59.999")
    }

    @Test
    fun `hours when needed`() {
        assertThat(DetailFormat.length(3_723_004)).isEqualTo("1:02:03.004")
        assertThat(DetailFormat.length(36_000_000)).isEqualTo("10:00:00.000")
    }

    @Test
    fun `a negative length is shown as zero`() {
        assertThat(DetailFormat.length(-5)).isEqualTo("0:00.000")
    }
}
