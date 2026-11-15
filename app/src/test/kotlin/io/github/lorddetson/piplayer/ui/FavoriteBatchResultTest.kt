package io.github.lorddetson.piplayer.ui

import io.github.lorddetson.piplayer.userdata.Favorites
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The message of favorites changed for selected songs counts what changed and what is still being read (library.md §20.3). */
class FavoriteBatchResultTest {

    @Test
    fun `the songs without an identity and those whose identity went are still being read, each song once`() {
        // Songs 1 to 4 are selected, song 2 twice (a playlist may hold a song twice); song 3 has no identity yet.
        val identities = mapOf(1L to 11L, 2L to 12L, 4L to 14L)

        val result = favoriteBatchResult(listOf(1L, 2L, 3L, 2L, 4L), identities::get, Favorites.Written(changed = 1, gone = 1))

        assertThat(result).isEqualTo(FavoriteChoices.BatchResult(changed = 1, notRead = 2))
    }
}
