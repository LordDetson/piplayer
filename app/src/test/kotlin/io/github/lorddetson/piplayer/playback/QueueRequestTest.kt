package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ArtistLink
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueRequestTest {

    private fun song(id: Long) = Song(id, "content://media/external/audio/media/$id", "Song $id", null, null, null)

    private val songs = listOf(song(30), song(10), song(20))

    @Test
    fun `the request travels as a media ID that names the list, its order, its search and the tapped song`() {
        val request = QueueRequest(startSongId = 42, startRow = 7)

        assertThat(request.mediaId).isEqualTo("q2/songs?key=&sort=title&rev=0&q=&start=42&index=7")
        assertThat(QueueRequest.parse(request.mediaId)).isEqualTo(request)
    }

    @Test
    fun `every list survives the round trip with its key, sort, reverse and search`() {
        val lists = listOf(
            SongQuery.SONGS.copy(sort = SongSort.LENGTH, reversed = true, search = "ёжик & co = 100%"),
            SongQuery.album("Альбом\u001Fartist\u001Fexternal_primary\u001Fmusic/a b/\u001F0"),
            SongQuery.album("mbid:5b11f4ce-a62d-471e-81fc-a69a8278c7da").copy(search = "+"),
            SongQuery.artist("ac dc").copy(sort = SongSort.TITLE),
            SongQuery.folder("external_primary", "Music/Rock & Roll/?#%/"),
            SongQuery.folder("1234-5678", ""),
            SongQuery.tree("external_primary", "Music/").copy(sort = SongSort.TITLE, reversed = true),
        )

        for (list in lists) {
            val request = QueueRequest(startSongId = -3, startRow = 12, list = list)
            assertThat(QueueRequest.parse(request.mediaId)).describedAs(request.mediaId).isEqualTo(request)
            assertThat(request.mediaId).describedAs("one item of a media ID").doesNotContain(" ", "\u001F", "#")
        }
    }

    @Test
    fun `each list starts with its own default sort`() {
        assertThat(SongQuery.SONGS.sort).isEqualTo(SongSort.TITLE)
        assertThat(SongQuery.album("a").sort).isEqualTo(SongSort.TRACK)
        assertThat(SongQuery.artist("a").sort).isEqualTo(SongSort.YEAR)
        assertThat(SongQuery.folder("v", "a/").sort).isEqualTo(SongSort.FILE_NAME)
        assertThat(SongQuery.tree("v", "a/").sort).isEqualTo(SongSort.FILE_NAME)
    }

    @Test
    fun `a folder key holds the volume and the path`() {
        val list = SongQuery.folder("external_primary", "Music/A/B/")

        assertThat(list.key).isEqualTo("external_primary/Music/A/B/")
        assertThat(FolderKey.parse(list.key)).isEqualTo(FolderKey("external_primary", "Music/A/B/"))
        assertThat(FolderKey.parse("1234-5678/")).isEqualTo(FolderKey("1234-5678", ""))
        assertThat(FolderKey.parse("no-slash")).isNull()
    }

    @Test
    fun `a saved request of version 1 still names the song list by title`() {
        assertThat(QueueRequest.parse("q1/songs?sort=title&start=42&index=7"))
            .isEqualTo(QueueRequest(startSongId = 42, startRow = 7, list = SongQuery.SONGS))
    }

    @Test
    fun `any other media ID names no request`() {
        val others = listOf(
            "", "42", "content://media/external/audio/media/42", "q1/songs", "q1/songs?sort=title&start=42",
            "q1/songs?sort=title&start=x&index=1", "q1/songs?sort=artist&start=42&index=7",
            "q1/albums?sort=title&start=42&index=7", "q2/songs?sort=title&start=42&index=7",
            "q1/songs?sort=title&start=42&index=-1", "q1/songs?sort=title&start=42&index=7&extra=1",
            "q2/albums?key=a&sort=title&rev=0&q=&start=1&index=0",
            "q2/album?key=a&sort=pitch&rev=0&q=&start=1&index=0",
            "q2/album?key=a&sort=track&rev=2&q=&start=1&index=0",
            "q2/album?key=&sort=track&rev=0&q=&start=1&index=0",
            "q2/songs?key=x&sort=title&rev=0&q=&start=1&index=0",
            "q2/folder?key=no-slash&sort=file&rev=0&q=&start=1&index=0",
            "q2/album?key=%E0%A4&sort=track&rev=0&q=&start=1&index=0",
            "q2/album?key=a&sort=track&rev=0&q=&start=1&index=0&extra=1",
            "q2/album?key=a&sort=track&rev=0&q=&start=1&index=-1",
        )

        for (mediaId in others) assertThat(QueueRequest.parse(mediaId)).describedAs(mediaId).isNull()
    }

    @Test
    fun `the queue starts at the tapped song wherever it is now`() {
        assertThat(QueueRequest.startIndex(songs, QueueRequest(startSongId = 20, startRow = 0))).isEqualTo(2)
    }

    @Test
    fun `a song that is gone leaves the queue starting at its row, or the last one`() {
        assertThat(QueueRequest.startIndex(songs, QueueRequest(startSongId = 99, startRow = 1))).isEqualTo(1)
        assertThat(QueueRequest.startIndex(songs, QueueRequest(startSongId = 99, startRow = 5))).isEqualTo(2)
    }

    @Test
    fun `an empty list has no start`() {
        assertThat(QueueRequest.startIndex(emptyList(), QueueRequest(startSongId = 20, startRow = 0))).isEqualTo(-1)
    }

    private val links = SongLinks(
        albumKey = "new album key",
        artists = listOf(ArtistLink("new artist", "New Artist")),
        folder = FolderKey("external_primary", "Music/A/"),
    )

    @Test
    fun `a saved album or artist whose key names nothing any more follows the saved song`() {
        val album = QueueRequest(7, 3, SongQuery.album("old album key").copy(sort = SongSort.TITLE, search = "x"))
        val artist = QueueRequest(7, 3, SongQuery.artist("old artist"))

        assertThat(QueueRequest.rekeyed(album, keyExists = false, links = links))
            .isEqualTo(album.copy(list = album.list.copy(key = "new album key")))
        assertThat(QueueRequest.rekeyed(artist, keyExists = false, links = links))
            .isEqualTo(artist.copy(list = artist.list.copy(key = "new artist")))
    }

    @Test
    fun `a request whose key still holds, or with nothing to follow, stays as it is`() {
        val album = QueueRequest(7, 3, SongQuery.album("old album key"))
        val folder = QueueRequest(7, 3, SongQuery.tree("external_primary", "Music/"))

        assertThat(QueueRequest.rekeyed(album, keyExists = true, links = links)).isEqualTo(album)
        assertThat(QueueRequest.rekeyed(album, keyExists = false, links = null)).isEqualTo(album)
        assertThat(QueueRequest.rekeyed(album, keyExists = false, links = links.copy(albumKey = null))).isEqualTo(album)
        assertThat(QueueRequest.rekeyed(folder, keyExists = false, links = links)).isEqualTo(folder)
        assertThat(QueueRequest.rekeyed(QueueRequest(7, 3), keyExists = false, links = links)).isEqualTo(QueueRequest(7, 3))
    }
}
