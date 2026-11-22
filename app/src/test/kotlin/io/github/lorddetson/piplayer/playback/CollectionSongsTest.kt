package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CollectionSongsTest {
    @Test
    fun `folders resolve recursively and the first available copy wins across collections`() {
        val playlist = SongQuery.playlist(7)
        val folder = SongQuery.tree("external_primary", "Music/Live/")
        val smart = SongQuery.smart(ListKind.FAVORITES)
        val artist = SongQuery.artist("famous")
        val targets = listOf(playlist, folder, smart, artist)
        val stored = targets.map { QueueRequest(-1, 0, it).mediaId }
        val requested = requireNotNull(collectionRequests(stored))
        val copies = mapOf(
            playlist to listOf(song(3, "First title"), song(2)),
            folder to listOf(song(3, "Later title"), song(1)),
            smart to listOf(song(4), song(1)),
            artist to listOf(song(2)),
        )

        val result = collectionSongs(requested, copies::getValue)

        assertThat(requested).containsExactlyElementsOf(targets)
        assertThat(result.map(Song::id)).containsExactly(3L, 2L, 1L, 4L)
        assertThat(result.first().title).isEqualTo("First title")
    }

    @Test
    fun `a broken source cannot replace the queue with a partial result`() {
        val valid = QueueRequest(-1, 0, SongQuery.playlist(7)).mediaId
        assertThat(collectionRequests(listOf(valid, "bad"))).isNull()
        assertThat(collectionRequests(listOf(QueueRequest(-1, 0, SongQuery.SONGS).mediaId))).isNull()
        assertThat(collectionRequests(emptyList())).isNull()
    }

    private fun song(id: Long, title: String = "Song $id") = Song(id, "content://track/$id", title, null, null, null)
}
