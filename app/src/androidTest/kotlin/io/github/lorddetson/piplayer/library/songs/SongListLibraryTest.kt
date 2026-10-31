package io.github.lorddetson.piplayer.library.songs

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The shared song list over the library database (docs/library.md §1.4). */
@RunWith(AndroidJUnit4::class)
class SongListLibraryTest {

    private val context = TestFiles.targetContext
    private val gate = CountDownLatch(1)
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(row(1, "Alpha"), row(2, "Bravo"))
        this.gate = this@SongListLibraryTest.gate
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    @After
    fun openTheGate() = gate.countDown()

    @Test
    fun aLoadDuringTheFirstSyncWaitsForTheLibrary() {
        // As the playback service restoring a saved queue while the first sync still runs.
        var loaded: List<Song>? = null
        val done = CountDownLatch(1)
        thread {
            loaded = SongList.load(context)
            done.countDown()
        }
        assertThat(done.await(1, TimeUnit.SECONDS)).describedAs("the load waits").isFalse()

        gate.countDown()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(loaded!!.map { it.title }).containsExactly("Alpha", "Bravo")
    }

    @Test
    fun aListKeptBeforeTheLibraryChangedIsNotHandedOut() = runBlocking<Unit> {
        gate.countDown()
        Library.sync(context).awaitIdle()
        SongList.load(context)
        assertThat(SongList.current()).hasSize(2)

        index.volume().rows = listOf(row(1, "Alpha"), row(2, "Bravo"), row(3, "Charlie"))
        Library.sync(context).request()
        Library.sync(context).awaitIdle()

        assertThat(SongList.current()).isNull()
        assertThat(SongList.load(context).map { it.title }).containsExactly("Alpha", "Bravo", "Charlie")
        assertThat(SongList.current()).hasSize(3)
    }

    @Test
    fun startingTheLibraryAgainDoesNotSyncAgain() = runBlocking<Unit> {
        gate.countDown()
        Library.sync(context).awaitIdle()
        val reads = index.rowReads

        // As every new Songs screen that sees the permission.
        Library.start(context)
        Library.sync(context).awaitIdle()

        assertThat(index.rowReads).isEqualTo(reads)
    }
}
