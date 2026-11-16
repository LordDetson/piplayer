package io.github.lorddetson.piplayer.library.mirror

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * When a Skip lapses (TI §1): once no file of its track holds the size and modification time it stored. The library
 * stores modification times in seconds, a Skip in milliseconds (TI §10.2).
 */
class SkipLapseTest {

    private val size = 5_000_000L
    private val mtimeSeconds = 1_700_000_000L
    private val skip = SkipLapse.Skip(decisionId = 7, identityId = 3, size = size, mtimeMs = mtimeSeconds * 1_000)

    private fun file(size: Long = this.size, mtimeSeconds: Long = this.mtimeSeconds) = SkipLapse.FileStamp(size, mtimeSeconds)

    @Test
    fun `a Skip lasts while a file of its track holds its size and modification time`() {
        assertThat(SkipLapse.lapses(skip, listOf(file()))).isFalse()
        // A byte-identical copy that appears, with a time of its own, beside the skipped file.
        assertThat(SkipLapse.lapses(skip, listOf(file(mtimeSeconds = 1_800_000_000), file()))).isFalse()
    }

    @Test
    fun `a file changed by a tagger lets it lapse`() {
        assertThat(SkipLapse.lapses(skip, listOf(file(size = size + 4_096)))).isTrue()
        assertThat(SkipLapse.lapses(skip, listOf(file(mtimeSeconds = mtimeSeconds + 1)))).isTrue()
        // Its copy kept the old size, but not the time.
        assertThat(SkipLapse.lapses(skip, listOf(file(size = size + 4_096), file(mtimeSeconds = 1_800_000_000)))).isTrue()
    }

    @Test
    fun `a track whose file left its key holds no file, and its Skip lapses`() {
        assertThat(SkipLapse.lapses(skip, emptyList())).isTrue()
    }

    @Test
    fun `a Skip that stored no size or time lapses at its first check`() {
        assertThat(SkipLapse.lapses(skip.copy(size = null), listOf(file()))).isTrue()
        assertThat(SkipLapse.lapses(skip.copy(mtimeMs = null), listOf(file()))).isTrue()
    }

    @Test
    fun `the times compare to the second the library keeps`() {
        assertThat(SkipLapse.lapses(skip.copy(mtimeMs = mtimeSeconds * 1_000 + 500), listOf(file()))).isTrue()
        assertThat(SkipLapse.mtimeMs(mtimeSeconds)).isEqualTo(mtimeSeconds * 1_000)
    }
}
