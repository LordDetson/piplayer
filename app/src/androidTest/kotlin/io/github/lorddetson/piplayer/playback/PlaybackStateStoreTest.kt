package io.github.lorddetson.piplayer.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    }

    @Test
    fun `a saved snapshot is read back`() {
        runBlocking {
            val saved = snapshot(positionMs = 65_432)

            store.write(saved)

            assertThat(store.read()).isEqualTo(saved)
        }
    }

    @Test
    fun `the latest snapshot wins over one still queued`() {
        runBlocking {
            val first = snapshot(positionMs = 1_000)
            val second = snapshot(positionMs = 2_000)

            store.enqueue(first)
            store.enqueue(second)

            assertThat(store.read()).isEqualTo(second)
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
                assertThat(reopened.first.read()).isEqualTo(saved)
            } finally {
                close(reopened.first, reopened.second)
            }
        }
    }

    private fun open(): Pair<PlaybackStateStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return PlaybackStateStore(dataStore) to scope
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
