package io.github.lorddetson.piplayer.ui.playlists

import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.songs.SongsState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The header of a smart list's page (docs/library.md §17.2): its number of songs and Shuffle stand for the whole list, so
 * neither comes from the first page that the page shows while it reads the rest.
 */
class SmartListHeaderTest {

    private fun songs(count: Int) = List(count) { Song(it.toLong(), "content://$it", "Song $it", null, null, null) }

    private val firstPage = SongsState.Loaded(songs(50), SongQuery.favorites(), complete = false)
    private val whole = SongsState.Loaded(songs(3_000), SongQuery.favorites())

    @Test
    fun `the first page shows no number of songs and the whole list its own`() {
        assertThat(SmartListHeader.count(firstPage)).isNull()
        assertThat(SmartListHeader.count(whole)).isEqualTo(3_000)
        assertThat(SmartListHeader.count(null)).isNull()
    }

    @Test
    fun `shuffle waits for the whole list and needs a song`() {
        assertThat(SmartListHeader.mayShuffle(firstPage)).isFalse()
        assertThat(SmartListHeader.mayShuffle(whole)).isTrue()
        assertThat(SmartListHeader.mayShuffle(SongsState.Loaded(emptyList(), SongQuery.favorites()))).isFalse()
    }

    @Test
    fun `play may start from the first page, whose first song is the list's first`() {
        assertThat(SmartListHeader.mayPlay(firstPage)).isTrue()
        assertThat(SmartListHeader.mayPlay(SongsState.Loaded(emptyList(), SongQuery.favorites(), complete = false))).isFalse()
    }
}
