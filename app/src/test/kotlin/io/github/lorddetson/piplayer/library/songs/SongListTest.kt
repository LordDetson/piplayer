package io.github.lorddetson.piplayer.library.songs

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SongListTest {

    private fun song(id: Long, title: String) = Song(id, "file:///$id.mp3", title, null, null, null)

    @AfterEach
    fun forget() = SongList.forget()

    @Test
    fun `the list is kept in title order`() {
        SongList.load(SongSource { listOf(song(2, "B"), song(1, "A")) })

        assertThat(SongList.current()!!.map { it.id }).containsExactly(1L, 2L)
    }

    @Test
    fun `an older read that ends last does not replace a newer one`() {
        val release = CountDownLatch(1)
        val older = thread {
            SongList.load(SongSource {
                release.await(5, TimeUnit.SECONDS)
                listOf(song(1, "Deleted since"))
            })
        }
        Thread.sleep(100)

        SongList.load(SongSource { listOf(song(2, "Newer")) })
        release.countDown()
        older.join(5_000)

        assertThat(SongList.current()!!.map { it.title }).containsExactly("Newer")
    }
}
