package io.github.lorddetson.piplayer.library.sort

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Sort keys compare as SQLite compares BLOBs, and their primary part finds letters (docs/library.md §11.1, §11.2). */
class SortKeyTest {

    private fun key(vararg bytes: Int) = SortKey(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `keys compare byte by byte without sign, a prefix first`() {
        assertThat(key(0x7f)).isLessThan(key(0x80))
        assertThat(key(0x30, 0xff)).isLessThan(key(0x31))
        assertThat(key(0x30)).isLessThan(key(0x30, 0x01))
        assertThat(SortKey.EMPTY).isLessThan(key(0x02))
        assertThat(key(0x30, 0x31).compareTo(key(0x30, 0x31))).isZero()
        assertThat(listOf(key(0xa0), key(0x05), key(0x05, 0x00), key(0xff)).sorted())
            .containsExactly(key(0x05), key(0x05, 0x00), key(0xa0), key(0xff))
    }

    @Test
    fun `the primary part ends at the first level separator`() {
        assertThat(key(0x30, 0x31, 0x01, 0x05, 0x01, 0x05, 0x00).primary).isEqualTo(key(0x30, 0x31))
        assertThat(key(0x01, 0x05, 0x00).primary).isEqualTo(SortKey.EMPTY)
        assertThat(key(0x30, 0x31, 0x00).primary).isEqualTo(key(0x30, 0x31))
        assertThat(SortKey.EMPTY.primary).isEqualTo(SortKey.EMPTY)
    }

    @Test
    fun `keys are equal by their bytes`() {
        assertThat(key(1, 2, 3)).isEqualTo(key(1, 2, 3)).hasSameHashCodeAs(key(1, 2, 3))
        assertThat(key(1, 2, 3)).isNotEqualTo(key(1, 2))
        assertThat(SortKey.of(null)).isNull()
    }
}
