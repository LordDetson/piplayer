package io.github.lorddetson.piplayer.library.lists

import io.github.lorddetson.piplayer.library.values.MergedValues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The reasons of Songs missing information as the list looks them up (docs/library.md §17.1, §17.4). */
class MissingReasonsTest {

    @Test
    fun `the reasons are the bits the resolver stores`() {
        assertThat(MissingReasons.ORDER).containsExactly(
            MergedValues.TITLE_FROM_FILE_NAME, MergedValues.NO_ARTIST, MergedValues.NO_ALBUM, MergedValues.NO_COVER,
            MergedValues.NO_RECORDING_ID,
        )
        assertThat(MissingReasons.ALL).isEqualTo(31)
        // Title, artist and album are on by default; cover and MusicBrainz ID are not.
        assertThat(MissingReasons.DEFAULT).isEqualTo(MissingReasons.TITLE or MissingReasons.ARTIST or MissingReasons.ALBUM)
    }

    @Test
    fun `the stored sets of one reason are every set that holds it`() {
        assertThat(MissingReasons.storedSets(MissingReasons.TITLE)).containsExactly(1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31)
        assertThat(MissingReasons.storedSets(MissingReasons.RECORDING_ID)).containsExactlyElementsOf(16..31)
    }

    @Test
    fun `the stored sets of several reasons hold at least one of them, in ascending order`() {
        val sets = MissingReasons.storedSets(MissingReasons.DEFAULT)

        assertThat(sets).containsExactlyElementsOf((1..31).filter { it and 7 != 0 })
        assertThat(sets).hasSize(28).doesNotContain(0, 8, 16, 24)
        assertThat(MissingReasons.storedSets(MissingReasons.ALL)).containsExactlyElementsOf(1..31)
    }

    @Test
    fun `no chosen reason has no stored set, and neither has a file without reasons`() {
        assertThat(MissingReasons.storedSets(0)).isEmpty()
        assertThat(MissingReasons.storedSets(MissingReasons.ALL)).doesNotContain(0)
    }
}
