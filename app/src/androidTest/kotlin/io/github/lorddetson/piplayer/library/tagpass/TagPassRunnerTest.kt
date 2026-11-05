package io.github.lorddetson.piplayer.library.tagpass

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.tagging.TagReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** When the tag pass runs (docs/library.md §5.5). */
@RunWith(AndroidJUnit4::class)
class TagPassRunnerTest {

    private val database = LibraryDatabase.open(TestFiles.targetContext, file = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(database, index, mayRead = { true }, FakeRules())
    private val gate = PassGate()
    private val reads = AtomicInteger()
    private val cancelled = AtomicBoolean()
    private var reader = PassReader { read() }

    private suspend fun read(): PassRead {
        reads.incrementAndGet()
        return PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf("Tag"))))
    }

    private val pass by lazy {
        TagPass(database, sync, { reader.read(it) }, cyrillicLanguage = { false }, gate = gate::awaitOpen, batchSize = 1)
    }
    private val runner by lazy { TagPassRunner(pass, sync.status, gate, startDelay = 10.milliseconds) }

    @After
    fun close() = runBlocking {
        runner.stopAndJoin()
        sync.close()
        database.close()
        TagPassState.progress.value = PassProgress()
        SongList.forget()
    }

    private fun read(count: Int) = eventually("$count files were read") { reads.get() == count }

    private suspend fun library(vararg ids: Long) {
        index.volume().rows = ids.map { row(it, generation = it) }
        sync.sync()
    }

    @Test
    fun nothingIsReadUntilTheLibraryKnowsItsFiles() = runBlocking<Unit> {
        gate.setUiVisible(true)
        runner.start()
        Thread.sleep(300)
        assertThat(reads.get()).isZero()

        library(1, 2)

        read(2)
    }

    @Test
    fun nothingIsReadWhileTheGateIsClosed() = runBlocking<Unit> {
        library(1, 2)
        runner.start()
        Thread.sleep(300)
        assertThat(reads.get()).isZero()

        gate.setPlaying(true)

        read(2)
    }

    @Test
    fun aClosedGateHoldsARunBetweenFilesWithoutCancellingARead() = runBlocking<Unit> {
        library(1, 2, 3)
        reader = PassReader {
            try {
                if (reads.get() == 0) gate.setUiVisible(false)
                read()
            } catch (e: CancellationException) {
                cancelled.set(true)
                throw e
            }
        }
        gate.setUiVisible(true)
        runner.start()

        read(1)
        Thread.sleep(300)
        assertThat(reads.get()).isEqualTo(1)
        assertThat(cancelled.get()).isFalse()

        gate.setUiVisible(true)
        read(3)
    }

    @Test
    fun aSyncThatAddsFilesBringsAnotherRun() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        runner.start()
        read(1)

        library(1, 2)

        read(2)
    }

    @Test
    fun aRequestDuringARunBringsOneMoreAfterIt() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        val hold = CountDownLatch(1)
        reader = PassReader {
            hold.await()
            read()
        }
        runner.start()
        Thread.sleep(100)

        library(1, 2)
        runner.runAgain()
        hold.countDown()

        read(2)
    }

    @Test
    fun aRequestReadsWhatNoSyncWouldHaveBrought() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        runner.start()
        read(1)

        dao().readAgain(listOf(dao().files().single().id))
        runner.runAgain()

        read(2)
    }

    private fun dao() = database.library()

    @Test
    fun theProgressReachesTheLists() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1, 2)
        runner.start()

        read(2)

        eventually("the progress says it ended") {
            TagPassState.progress.value.let { !it.running && it.total == 2 && it.done == 2 }
        }
    }

    @Test
    fun aFailingRunLeavesTheRunnerAlive() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        reader = PassReader { throw IllegalStateException("The reader gave up") }
        runner.start()
        Thread.sleep(300)
        reader = PassReader { read() }

        runner.runAgain()

        read(1)
    }

    @Test
    fun stoppingEndsTheRunner() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        val entered = CountDownLatch(1)
        reader = PassReader {
            entered.countDown()
            kotlinx.coroutines.delay(60_000)
            read()
        }
        runner.start()
        entered.await()

        runner.stopAndJoin()

        assertThat(reads.get()).isZero()
    }

    @Test
    fun theOrderIsFreeAgainAfterARunThatChangedNothing() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        reader = PassReader { PassRead.OpenFailed }
        runner.start()

        eventually("the run ended") { TagPassState.progress.value.let { it.total == 1 && !it.running } }

        assertThat(SongList.resortDue()).isFalse()
    }
}
