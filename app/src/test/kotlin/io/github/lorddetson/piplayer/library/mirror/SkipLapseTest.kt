package io.github.lorddetson.piplayer.library.mirror

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * When a Skip lapses (TI §1): once no file of its track holds the size and modification time it stored, while no file
 * without a key yet holds them either. The library stores modification times in seconds, a Skip in milliseconds (TI §10.2).
 */
class SkipLapseTest {

    private val key = "k1"
    private val size = 5_000_000L
    private val mtimeMs = 1_700_000_000_000L

    private fun lapses(vararg holders: String?) = SkipLapse.lapses(key, size, mtimeMs, holders.toList())

    @Test
    fun `a Skip lasts while a file of its track holds its size and modification time`() {
        assertThat(lapses(key)).isFalse()
        // A byte-identical copy beside the skipped file has a time of its own, so it holds nothing; another track's file
        // with the same stamp does not count, the skipped file does.
        assertThat(lapses("other", key)).isFalse()
    }

    @Test
    fun `a file changed by a tagger, or one that left the track's key, lets it lapse`() {
        // No file holds the stamp any more.
        assertThat(lapses()).isTrue()
        // Only a file of another track holds it: the skipped file left the track's key.
        assertThat(lapses("other")).isTrue()
    }

    @Test
    fun `a file without a key yet that holds the stamp may be the skipped file, so the Skip stays`() {
        // After a rebuild, while the copy is read first; or when the skipped file's read failed.
        assertThat(lapses(null)).isFalse()
        assertThat(lapses("other", null)).isFalse()
    }

    @Test
    fun `a Skip that stored no size or time lapses at its first check`() {
        assertThat(SkipLapse.lapses(key, null, mtimeMs, listOf(key))).isTrue()
        assertThat(SkipLapse.lapses(key, size, null, listOf(key))).isTrue()
    }

    @Test
    fun `the times convert to and from the second the library keeps`() {
        assertThat(SkipLapse.mtimeMs(1_700_000_000)).isEqualTo(mtimeMs)
        assertThat(SkipLapse.mtimeSeconds(mtimeMs)).isEqualTo(1_700_000_000)
        // A time no file of the library can hold.
        assertThat(SkipLapse.mtimeSeconds(mtimeMs + 500)).isNull()
    }
}
