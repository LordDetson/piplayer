package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.identity.TrackRef
import io.github.lorddetson.piplayer.library.songs.Song
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LibrarySongSnapshotTest {
    @Test
    fun `ordered snapshot batches both reads and keeps duplicate occurrences and tapped position`() {
        val ids = listOf(99L, 4L, 2L, 4L, 98L, 3L, 97L)
        val songReads = mutableListOf<List<Long>>()
        val trackReads = mutableListOf<List<Long>>()
        val result = resolveSongSnapshot(
            ids,
            songsById = { requested ->
                songReads += requested
                requested.filter { it < 10 }.distinct().sorted().associateWith(::song)
            },
            libraryId = { "library" },
            tracksOf = { songs ->
                trackReads += songs.map(Song::id)
                songs.map { TrackRef(it.id, it.id + 100, null, null, null) }
            },
        )

        assertThat(result.songs.map(Song::id)).containsExactly(4L, 2L, 4L, 3L)
        assertThat(result.tracks).isEqualTo(listOf(4L, 2L, 4L, 3L).map { TrackRef(it, it + 100, null, null, null) })
        assertThat(result.libraryId).isEqualTo("library")
        assertThat(ids.indices.map(result::startIndex)).containsExactly(0, 0, 1, 2, 3, 3, 3)
        assertThat(result.startIndex(-1)).isNull()
        assertThat(result.startIndex(ids.size)).isNull()
        assertThat(songReads).containsExactly(ids)
        assertThat(trackReads).containsExactly(listOf(4L, 2L, 4L, 3L))
    }

    @Test
    fun `snapshot with no available songs does not read references or a library id`() {
        assertThatThrownBy {
            resolveSongSnapshot(
                listOf(99L, 99L),
                songsById = { emptyMap() },
                libraryId = { error("No songs survived") },
                tracksOf = { error("No songs survived") },
            )
        }.isInstanceOf(UnsupportedOperationException::class.java).hasMessage("The items name no song of the library")
    }

    @Test
    fun `a snapshot accepts only the canonical positive decimal file IDs`() {
        assertThat(snapshotSongId("1")).isEqualTo(1L)
        assertThat(snapshotSongId(Long.MAX_VALUE.toString())).isEqualTo(Long.MAX_VALUE)
        assertThat(listOf("", "0", "01", "-3", "+3", "1/a2", "album:1", "9223372036854775808").map(::snapshotSongId))
            .containsOnlyNulls()
    }

    private fun song(id: Long) = Song(id, "content://track/$id", "Song $id", null, null, null)
}
