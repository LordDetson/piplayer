package io.github.lorddetson.piplayer.library.tagpass

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.songs.SongSource
import io.github.lorddetson.piplayer.library.sync.LibraryStatus
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import java.nio.charset.Charset
import kotlinx.coroutines.flow.first
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.tagging.TagReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private var manual: LegacyEncoding? = null

    private suspend fun read(): PassRead {
        reads.incrementAndGet()
        return PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf("Tag"))))
    }

    private val pass by lazy {
        TagPass(
            database, sync, { reader.read(it) }, cyrillicLanguage = { false }, legacyEncoding = { manual }, gate = gate::awaitOpen,
            batchSize = 1,
            crashBackoff = 1.milliseconds, onBatch = SongList::applyInPlace,
        )
    }
    private var status: Flow<LibraryStatus>? = null
    private val runner by lazy { TagPassRunner(pass, status ?: sync.status, gate, startDelay = 10.milliseconds) }

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
        library(1, 2)
        val known = MutableStateFlow(LibraryStatus(ready = true, known = false))
        status = known
        runner.start()
        Thread.sleep(300)
        assertThat(reads.get()).isZero()

        known.value = LibraryStatus(ready = true, known = true)

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
                if (reads.get() == 0) {
                    gate.setUiVisible(false)
                    // A cancellation would land here.
                    delay(200)
                }
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
        Thread.sleep(200)
        assertThat(reads.get()).describedAs("no file read twice").isEqualTo(3)
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
        library(1, 2)
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        reader = PassReader {
            if (reads.get() == 1) {
                entered.countDown()
                hold.await()
            }
            read()
        }
        runner.start()
        entered.await()

        // During the read of the second file; no sync moves anything.
        dao().readAgain(listOf(dao().files().first().id))
        runner.runAgain()
        hold.countDown()

        read(3)
    }

    @Test
    fun aReadOfEverythingAgainAlsoTakesWhatFailedInThisProcess() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        reader = PassReader { PassRead.OpenFailed }
        runner.start()
        eventually("the run ended") { TagPassState.progress.value.let { it.total == 1 && !it.running } }
        reader = PassReader { read() }

        dao().forgetReaderVersions()
        runner.readAllAgain()

        read(1)
    }

    @Test
    fun aTimeoutInsideARunIsNotTakenForTheRunnerBeingStopped() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        reader = PassReader { withTimeout(1) { delay(1_000) }; read() }
        runner.start()
        Thread.sleep(300)
        reader = PassReader { read() }

        runner.runAgain()

        read(1)
    }

    @Test
    fun aRequestReadsWhatNoSyncWouldHaveBrought() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        runner.start()
        read(1)
        // Once the read is stored; before that, storing it would settle the file again.
        eventually("the run ended") { TagPassState.progress.value.let { it.total == 1 && !it.running } }

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
        library(1, 2)
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        reader = PassReader { PassRead.OpenFailed }
        runner.start()

        // The runner ends the freeze after the progress says the run ended.
        eventually("the order is free", 5.seconds) {
            TagPassState.progress.value.let { it.total == 2 && !it.running } &&
                SongList.load(SongSource { listOf(song(2, "A"), song(1, "B")) }).first().id == 2L
        }
    }

    @Test
    fun aRunFreezesTheOrderFromItsStart() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        SongList.load(SongSource { listOf(song(1, "A"), song(2, "B")) })
        var order = emptyList<Long>()
        reader = PassReader {
            // Before the first batch: a read now keeps the order of the list.
            order = SongList.load(SongSource { listOf(song(2, "A"), song(1, "B")) }).map { it.id }
            read()
        }
        runner.start()

        read(1)

        assertThat(order).containsExactly(1L, 2L)
    }

    @Test
    fun aRunThatChangedRowsLeavesASortDue() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        SongList.load(SongSource { listOf(song(1, "A")) })
        assertThat(SongList.resortDue()).isFalse()
        runner.start()

        read(1)

        eventually("a sort is due") { SongList.resortDue() }
    }

    @Test
    fun aChangedLegacyEncodingDecodesTheLibraryAgainWithoutReadingAndTheKeptListsFollow() = runBlocking<Unit> {
        gate.setUiVisible(true)
        library(1)
        val kino = buildString {
            for (byte in "Кино".toByteArray(Charset.forName("windows-1251"))) append((LegacyText.MARK_BASE + (byte.toInt() and 0xFF)).toChar())
        }
        reader = PassReader {
            reads.incrementAndGet()
            PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf(kino))))
        }
        val fileId = dao().files().single().id
        SongList.load(SongSource { listOf(song(fileId, "Index title")) })
        val start = SongList.batches.value
        runner.start()
        withTimeout(10.seconds) { SongList.batches.first { it > start } }
        // No Cyrillic language here: the automatic mode reads Latin-1.
        assertThat(SongList.keptSong(fileId)?.title).isEqualTo("Êèíî")
        val afterRead = SongList.batches.value

        manual = LegacyEncodings.ALL.single { it.name == "windows-1251" }
        runner.redecode()

        withTimeout(10.seconds) { SongList.batches.first { it > afterRead } }
        assertThat(SongList.keptSong(fileId)?.title).isEqualTo("Кино")
        assertThat(reads.get()).describedAs("files read").isEqualTo(1)
    }

    private fun song(id: Long, title: String) = Song(id, "uri$id", title, null, null, null)
}
