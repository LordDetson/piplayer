package io.github.lorddetson.piplayer.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.library.identity.Place
import io.github.lorddetson.piplayer.library.identity.TrackRef
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The playback state file itself: one DataStore, its latest snapshot, and persistence across store instances. */
@RunWith(AndroidJUnit4::class)
class PlaybackStateStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "playback-state-${UUID.randomUUID()}.preferences_pb")
    private val queueFile = File(context.cacheDir, "queue-${UUID.randomUUID()}.bin")

    /** The library a store asks for track references (docs/playback.md §4.8). */
    private val tracks = object : PlaybackStateStore.TrackSource {
        @Volatile
        var libraryId = "lib-1"

        override suspend fun refOf(fileId: Long) =
            TrackRef(fileId, identityId = 9, contentKey = "aa", Place("external_primary", "Music/", "$fileId.mp3"), 180_000)

        override suspend fun libraryId() = libraryId
    }
    private lateinit var store: PlaybackStateStore
    private lateinit var scope: CoroutineScope

    @Before
    fun openStore() {
        val opened = open()
        store = opened.first
        scope = opened.second
    }

    @After
    fun closeStore() {
        close(store, scope)
        file.delete()
        queueFile.delete()
    }

    @Test
    fun `a library song gets its track reference and the library id when it is written`() {
        runBlocking {
            store.write(snapshot())

            val read = store.read()!!
            assertThat(read.current).isEqualTo(TrackRef(42, 9, "aa", Place("external_primary", "Music/", "42.mp3"), 180_000))
            assertThat(read.libraryId).isEqualTo("lib-1")
        }
    }

    @Test
    fun `file ids read under another library get no track reference`() {
        runBlocking {
            tracks.libraryId = "lib-2"

            store.write(snapshot().copy(libraryId = "lib-1"))

            assertThat(store.read()!!.current).isNull()
            assertThat(store.read()!!.libraryId).isEqualTo("lib-1")
        }
    }

    @Test
    fun `the queue file is written whole and read back and cleared with the snapshot`() {
        runBlocking {
            val queue = QueueFile(revision = 5, libraryId = null, order = listOf(EntryKey.Song(2), EntryKey.Song(1)))

            store.write(queue)
            store.write(snapshot().copy(queueRevision = 5))

            assertThat(store.readQueue()).isEqualTo(queue.copy(libraryId = "lib-1"))
            assertThat(store.read()!!.queueRevision).isEqualTo(5)
            store.clear()
            assertThat(store.readQueue()).isNull()
            assertThat(queueFile.exists()).isFalse()
        }
    }

    @Test
    fun `a damaged queue file is no queue`() {
        runBlocking {
            queueFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))

            assertThat(store.readQueue()).isNull()
        }
    }

    @Test
    fun `a saved snapshot is read back`() {
        runBlocking {
            val saved = snapshot(positionMs = 65_432)

            store.write(saved)

            assertThat(store.read()?.copy(current = null, libraryId = null)).isEqualTo(saved)
        }
    }

    @Test
    fun `the latest snapshot wins over one still queued`() {
        runBlocking {
            val first = snapshot(positionMs = 1_000)
            val second = snapshot(positionMs = 2_000)

            store.enqueue(first)
            store.enqueue(second)

            assertThat(store.read()?.positionMs).isEqualTo(2_000)
        }
    }

    @Test
    fun `clear forgets the snapshot`() {
        runBlocking {
            store.write(snapshot())
            store.clear()

            assertThat(store.read()).isNull()
        }
    }

    @Test
    fun `a missing file has no snapshot`() {
        runBlocking {
            assertThat(store.read()).isNull()
        }
    }

    @Test
    fun `a snapshot survives a new store on the same file`() {
        runBlocking {
            val saved = snapshot(positionMs = 42_000)
            store.write(saved)
            close(store, scope)

            val reopened = open()
            try {
                assertThat(reopened.first.read()?.copy(current = null, libraryId = null)).isEqualTo(saved)
            } finally {
                close(reopened.first, reopened.second)
            }
        }
    }

    private fun open(): Pair<PlaybackStateStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return PlaybackStateStore(dataStore, queueFile, tracks) to scope
    }

    private fun close(store: PlaybackStateStore, scope: CoroutineScope) {
        store.close()
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
    }

    private fun snapshot(positionMs: Long = 0) = PlaybackSnapshot(
        queue = QueueRequest(startSongId = 42, startRow = 7),
        songId = 42,
        row = 7,
        positionMs = positionMs,
        shuffle = true,
        repeatMode = PlaybackSnapshot.REPEAT_ONE,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000,
        uri = "content://media/external/audio/media/42",
    )
}
