package io.github.lorddetson.piplayer.playback

import android.net.Uri
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.songs.SongSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.rules.ExternalResource

/**
 * Gives the song list a test's own songs instead of the media index for one test, and forgets them afterwards. The
 * playback service outlives a test, so it reads the source on every request.
 */
class SongSourceRule(private val initial: () -> List<Song> = { emptyList() }) : ExternalResource() {

    @Volatile
    var songs: List<Song> = emptyList()

    /** Thrown by the source instead of returning [songs], when set. */
    @Volatile
    var failure: Exception? = null

    /** How many times the source was read. */
    val reads: Int get() = readCount.get()

    private val readCount = AtomicInteger()

    override fun before() {
        songs = initial()
        SongList.sourceOverride = SongSource {
            readCount.incrementAndGet()
            failure?.let { throw it }
            songs
        }
        SongList.forget()
    }

    override fun after() {
        SongList.sourceOverride = null
        SongList.forget()
    }

    companion object {
        /** A song that plays a test asset, copied into the app's cache. */
        fun song(id: Long, title: String, asset: String = PlaybackTesting.SHORT_FIXTURE, artist: String? = "Artist $id") =
            Song(id, Uri.fromFile(TestFiles.copy(asset)).toString(), title, artist, "Album $id", 2_000L)

        /** A song whose file does not exist. */
        fun missing(id: Long, title: String) =
            Song(id, Uri.fromFile(File(TestFiles.targetContext.cacheDir, "missing-$id.mp3")).toString(), title, null, null, null)

        /** A song whose file is [bytes] under an audio name: text, an image. */
        fun fake(id: Long, title: String, bytes: ByteArray): Song {
            val file = File(TestFiles.targetContext.cacheDir, "fake-$id.mp3").apply { writeBytes(bytes) }
            return Song(id, Uri.fromFile(file).toString(), title, null, null, null)
        }
    }
}
