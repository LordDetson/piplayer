package io.github.lorddetson.piplayer.library.tagpass

import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What is stored about a file's length (docs/library.md §7.2). */
class StoredDurationTest {

    private val walk = DurationPlan(null, DurationCheck.WALK)
    private val flac = DurationPlan(180_000, DurationCheck.FLAC_END)
    private val header = DurationPlan(5_000, DurationCheck.NONE)

    @Test
    fun `a read stores what the header settles and what waits for a walk`() {
        assertThat(StoredDuration.afterRead(header, null)).isEqualTo(StoredDuration(5_000, DurationSource.HEADER))
        assertThat(StoredDuration.afterRead(walk, null)).isEqualTo(StoredDuration(null, DurationSource.PENDING_WALK))
        assertThat(StoredDuration.afterRead(flac, null)).isEqualTo(StoredDuration(180_000, DurationSource.PENDING_FLAC_END))
    }

    @Test
    fun `a read of the same file again keeps what a walk settled`() {
        val walked = StoredDuration(12_013, DurationSource.WALK)
        val unknown = StoredDuration(null, DurationSource.UNKNOWN)

        assertThat(StoredDuration.afterRead(walk, walked)).isEqualTo(walked)
        assertThat(StoredDuration.afterRead(flac, walked)).isEqualTo(walked)
        assertThat(StoredDuration.afterRead(walk, unknown)).isEqualTo(unknown)
    }

    @Test
    fun `a read again that finds a header it can trust replaces a walked length`() {
        val walked = StoredDuration(12_013, DurationSource.WALK)

        assertThat(StoredDuration.afterRead(header, walked)).isEqualTo(StoredDuration(5_000, DurationSource.HEADER))
    }

    @Test
    fun `a read again of a file still waiting leaves it waiting`() {
        val waiting = StoredDuration(null, DurationSource.PENDING_WALK)

        assertThat(StoredDuration.afterRead(walk, waiting)).isEqualTo(waiting)
    }

    @Test
    fun `a walk stores its length`() {
        assertThat(StoredDuration.afterWalk(walk, 12_013)).isEqualTo(StoredDuration(12_013, DurationSource.WALK))
        assertThat(StoredDuration.afterWalk(flac, 700)).isEqualTo(StoredDuration(700, DurationSource.WALK))
    }

    @Test
    fun `a check of FLAC's end that confirms the header leaves it the header's`() {
        assertThat(StoredDuration.afterWalk(flac, 180_000)).isEqualTo(StoredDuration(180_000, DurationSource.HEADER))
    }

    @Test
    fun `a walk that found nothing is settled too, FLAC keeping its header`() {
        assertThat(StoredDuration.afterWalk(walk, null)).isEqualTo(StoredDuration(null, DurationSource.UNKNOWN))
        assertThat(StoredDuration.afterWalk(flac, null)).isEqualTo(StoredDuration(180_000, DurationSource.HEADER))
    }
}
