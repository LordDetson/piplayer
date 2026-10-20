package io.github.lorddetson.piplayer.playback

import io.github.lorddetson.piplayer.library.songs.Song
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueueRequestTest {

    private fun song(id: Long) = Song(id, "content://media/external/audio/media/$id", "Song $id", null, null, null)

    private val songs = listOf(song(30), song(10), song(20))

    @Test
    fun `the request travels as a media ID that names the list, its order and the tapped song`() {
        val request = QueueRequest(startSongId = 42, startRow = 7)

        assertThat(request.mediaId).isEqualTo("q1/songs?sort=title&start=42&index=7")
        assertThat(QueueRequest.parse(request.mediaId)).isEqualTo(request)
    }

    @Test
    fun `any other media ID names no request`() {
        val others = listOf(
            "", "42", "content://media/external/audio/media/42", "q1/songs", "q1/songs?sort=title&start=42",
            "q1/songs?sort=title&start=x&index=1", "q1/songs?sort=artist&start=42&index=7",
            "q1/albums?sort=title&start=42&index=7", "q2/songs?sort=title&start=42&index=7",
            "q1/songs?sort=title&start=42&index=-1", "q1/songs?sort=title&start=42&index=7&extra=1",
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
}
