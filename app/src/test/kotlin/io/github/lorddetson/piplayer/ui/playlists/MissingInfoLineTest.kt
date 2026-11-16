package io.github.lorddetson.piplayer.ui.playlists

import io.github.lorddetson.piplayer.library.lists.MissingReasons
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The line under a song of Songs missing information (docs/library.md §17.1, TI §1): its chosen reasons, then the Skip mark. */
class MissingInfoLineTest {

    private val label = { reason: Int -> "r$reason" }

    private fun line(reasons: Int?, skipped: Boolean) = missingInfoLine(reasons, skipped, label, "Skipped")

    @Test
    fun `a row shows its reasons in the order of the chips`() {
        assertThat(line(MissingReasons.ALBUM or MissingReasons.TITLE, skipped = false)).isEqualTo("r1 · r4")
        assertThat(line(MissingReasons.ALL, skipped = false)).isEqualTo("r1 · r2 · r4 · r8 · r16")
    }

    @Test
    fun `a skipped song shows Skipped after its reasons`() {
        assertThat(line(MissingReasons.ARTIST, skipped = true)).isEqualTo("r2 · Skipped")
    }

    @Test
    fun `other lists carry no reasons and show no line`() {
        assertThat(line(null, skipped = false)).isNull()
        assertThat(line(0, skipped = false)).isNull()
    }
}
