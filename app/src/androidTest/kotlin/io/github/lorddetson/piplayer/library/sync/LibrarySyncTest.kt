package io.github.lorddetson.piplayer.library.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The sync on a fake media index and an in-memory library database (docs/library.md §1.3). */
@RunWith(AndroidJUnit4::class)
class LibrarySyncTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val dao = database.library()
    private val index = FakeMediaIndex()
    private var mayRead = true
    private val syncs = mutableListOf<LibrarySync>()

    private fun newSync() = LibrarySync(database, index, mayRead = { mayRead }).also { syncs += it }

    private val sync = newSync()

    @After
    fun close() {
        index.gate?.countDown()
        syncs.forEach { it.close() }
        database.close()
    }

    private fun files() = runBlocking { dao.files() }.associateBy { it.mediaStoreId }

    @Test
    fun aFirstSyncStoresEveryAudioRowWithItsKindsAndTheIndexValues() = runBlocking<Unit> {
        index.volume().rows = listOf(
            row(1, title = "Ёжик в тумане", artist = "<unknown>", album = "Сказки"),
            row(2, path = "Ringtones/", kinds = MediaKinds(music = false, ringtone = true)),
            row(3, title = null, name = "Без тегов.mp3", artist = null, album = null, durationMs = 0),
        )

        assertThat(sync.sync()).isTrue()

        val files = files()
        assertThat(files.keys).containsExactlyInAnyOrder(1L, 2L, 3L)
        with(files.getValue(2)) {
            assertThat(volumeName).isEqualTo(PRIMARY)
            assertThat(relativePath).isEqualTo("Ringtones/")
            assertThat(displayName).isEqualTo("2.mp3")
            assertThat(size).isEqualTo(1_002)
            assertThat(mtime).isEqualTo(1_700_000_002)
            assertThat(generationModified).isEqualTo(2)
            assertThat(mimeType).isEqualTo("audio/mpeg")
            assertThat(isMusic).isFalse()
            assertThat(isRingtone).isTrue()
            assertThat(isRecording).isFalse()
        }
        val entry = dao.entries().single { it.fileId == files.getValue(1).id }
        // The index's values are kept as it gives them; only what lists show is cleaned up.
        assertThat(entry.artist).isEqualTo("<unknown>")
        assertThat(entry.title).isEqualTo("Ёжик в тумане")
        assertThat(entry.year).isEqualTo(2_000)
        val displays = dao.displays().associateBy { it.fileId }
        with(displays.getValue(files.getValue(1).id)) {
            assertThat(title).isEqualTo("Ёжик в тумане")
            assertThat(artist).isNull()
            assertThat(titleKey).isEqualTo("ежик в тумане")
        }
        with(displays.getValue(files.getValue(3).id)) {
            assertThat(title).isEqualTo("Без тегов")
            assertThat(durationMs).isNull()
        }
        with(dao.volumes().single()) {
            assertThat(volumeName).isEqualTo(PRIMARY)
            assertThat(version).isEqualTo("1")
            assertThat(generation).isEqualTo(3)
        }
    }

    @Test
    fun valuesThatNeedEscapingArriveAsTheIndexGaveThem() = runBlocking<Unit> {
        val title = "He said \"hi\" \\ O'Brien\n\u0001 Ёжик 🎵"
        index.volume().rows = listOf(
            row(1, title = title, artist = "Back\\slash", name = "It's \"quoted\".mp3", path = "Music/A'B/"),
        )

        sync.sync()

        val file = files().getValue(1)
        assertThat(file.displayName).isEqualTo("It's \"quoted\".mp3")
        assertThat(file.relativePath).isEqualTo("Music/A'B/")
        assertThat(dao.entries().single().title).isEqualTo(title)
        assertThat(dao.entries().single().artist).isEqualTo("Back\\slash")
        assertThat(dao.displays().single().title).isEqualTo(title)
    }

    @Test
    fun aSyncWithoutChangesChangesNoFiles() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        val revision = sync.status.value.revision

        assertThat(sync.sync()).isFalse()

        assertThat(sync.status.value.revision).isEqualTo(revision)
        // Incremental: only the volume's rows changed since the stored generation are read, which are none.
        assertThat(files().keys).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun changedRowsAreStoredAgainUnderTheirRowIdAndNewOnesAdded() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        val before = files()

        index.volume().rows = listOf(row(1, title = "Renamed", name = "renamed.mp3", generation = 10), row(2), row(3, generation = 11))
        assertThat(sync.sync()).isTrue()

        val after = files()
        assertThat(after.getValue(1).id).isEqualTo(before.getValue(1).id)
        assertThat(after.getValue(1).displayName).isEqualTo("renamed.mp3")
        assertThat(after.getValue(1).generationModified).isEqualTo(10)
        assertThat(after.keys).containsExactlyInAnyOrder(1L, 2L, 3L)
        assertThat(dao.displays().single { it.fileId == before.getValue(1).id }.title).isEqualTo("Renamed")
        assertThat(dao.volumes().single().generation).isEqualTo(11)
    }

    @Test
    fun theUnsafeCountOfAnUnchangedFileSurvivesItsNextSync() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        dao.setUnsafeCount(files().getValue(1).id, 2)

        // A new generation and title, but the same size and modification time (docs/library.md §4.4).
        index.volume().rows = listOf(row(1, title = "Renamed", generation = 10), row(2))
        assertThat(sync.sync()).isTrue()

        assertThat(files().getValue(1).unsafeCount).isEqualTo(2)
        assertThat(files().getValue(2).unsafeCount).isZero()
    }

    @Test
    fun aChangedFileGetsNewChances() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        dao.setUnsafeCount(files().getValue(1).id, 2)
        dao.setUnsafeCount(files().getValue(2).id, 1)

        index.volume().rows = listOf(
            row(1, generation = 10).copy(size = 5_000),
            row(2, generation = 11).let { it.copy(modifiedSeconds = it.modifiedSeconds + 1) },
        )
        sync.sync()

        assertThat(files().getValue(1).unsafeCount).isZero()
        assertThat(files().getValue(2).unsafeCount).isZero()
    }

    @Test
    fun theUnsafeCountSurvivesAnIndexRebuild() = runBlocking<Unit> {
        val before = row(1, name = "a.mp3")
        index.volume().rows = listOf(before)
        sync.sync()
        dao.setUnsafeCount(dao.files().single().id, 1)

        index.volume().version = "2"
        index.volume().rows = listOf(
            row(2, name = "a.mp3", generation = 1).copy(size = before.size, modifiedSeconds = before.modifiedSeconds),
        )
        sync.sync()

        assertThat(dao.files().single().unsafeCount).isEqualTo(1)
    }

    @Test
    fun deletedRowsLeaveEveryTable() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        val removed = files().getValue(2).id

        index.volume().rows = listOf(row(1))
        assertThat(sync.sync()).isTrue()

        assertThat(files().keys).containsExactly(1L)
        assertThat(dao.entries().map { it.fileId }).doesNotContain(removed)
        assertThat(dao.displays().map { it.fileId }).doesNotContain(removed)
    }

    @Test
    fun anIndexRebuildKeepsTheRowIdsOfFilesAtTheSameLocation() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"), row(3, name = "c.mp3"))
        sync.sync()
        val before = dao.files().associateBy { it.displayName }

        // A rebuilt index: a new version, new IDs (two of them swapped), generations from the start, one file gone.
        index.volume().version = "2"
        index.volume().rows = listOf(row(2, name = "a.mp3", generation = 1), row(1, name = "b.mp3", generation = 2))
        assertThat(sync.sync()).isTrue()

        val after = dao.files().associateBy { it.displayName }
        assertThat(after.keys).containsExactlyInAnyOrder("a.mp3", "b.mp3")
        assertThat(after.getValue("a.mp3").id).isEqualTo(before.getValue("a.mp3").id)
        assertThat(after.getValue("a.mp3").mediaStoreId).isEqualTo(2)
        assertThat(after.getValue("b.mp3").id).isEqualTo(before.getValue("b.mp3").id)
        assertThat(after.getValue("b.mp3").mediaStoreId).isEqualTo(1)
        assertThat(dao.volumes().single().version).isEqualTo("2")
        assertThat(dao.volumes().single().generation).isEqualTo(2)
    }

    @Test
    fun aDeletionKeepsTheGenerationAndIsFoundByTheIdCheck() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        index.volume().rows = listOf(row(1))
        // As in the real index: a deletion does not move the generation, so this sync is incremental.
        assertThat(index.generation(PRIMARY)).isEqualTo(2)

        assertThat(sync.sync()).isTrue()

        assertThat(files().keys).containsExactly(1L)
        assertThat(index.fullReads).isEqualTo(1)
    }

    @Test
    fun anIndexRebuiltWhileTheSyncReadsIsSyncedAgainInFull() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "a.mp3"))
        sync.sync()
        val before = files().getValue(1).id
        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, generation = 5))
        // The index is rebuilt between the version check and the end of the reads: new version, new IDs.
        index.duringRead = {
            synchronized(index) {
                index.volume().version = "2"
                index.volume().rows = listOf(row(7, name = "a.mp3", generation = 1), row(8, name = "2.mp3", generation = 2))
            }
        }

        sync.sync()
        sync.awaitIdle()

        val after = dao.files().associateBy { it.displayName }
        assertThat(after.keys).containsExactlyInAnyOrder("a.mp3", "2.mp3")
        assertThat(after.getValue("a.mp3").id).isEqualTo(before)
        assertThat(after.getValue("a.mp3").mediaStoreId).isEqualTo(7)
        assertThat(dao.volumes().single().version).isEqualTo("2")
    }

    @Test
    fun aVolumeThatIsNoLongerAttachedLosesItsFilesAndItsState() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        index.volume("1234-5678").rows = listOf(row(2, volume = "1234-5678"))
        sync.sync()

        index.volumes.remove("1234-5678")
        assertThat(sync.sync()).isTrue()

        assertThat(files().keys).containsExactly(1L)
        assertThat(dao.volumes().map { it.volumeName }).containsExactly(PRIMARY)
    }

    @Test
    fun aVolumeWhoseVersionCannotBeReadKeepsItsFiles() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()

        index.volume().version = null
        index.volume().rows = emptyList()
        assertThat(sync.sync()).isFalse()

        assertThat(files().keys).containsExactly(1L)
    }

    @Test
    fun aChangeThatLandsWhileTheSyncReadsIsReadByTheNextSync() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        index.volume().rows = listOf(row(1), row(2, generation = 5))
        // While the sync reads, another file is added with a newer generation than the one the sync read first.
        index.duringRead = { synchronized(index) { index.volume().rows += row(3, generation = 6) } }
        sync.sync()
        assertThat(dao.volumes().single().generation).isEqualTo(5)

        sync.sync()

        assertThat(files().keys).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun aFailedSyncLeavesTheDatabaseAsItWasAndTheLibraryReady() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        index.volume("1234-5678").rows = listOf(row(2, volume = "1234-5678"))
        index.failingVolume = "1234-5678"
        index.failure = IllegalStateException("The index went away")

        assertThat(sync.sync()).isFalse()

        assertThat(dao.files()).isEmpty()
        assertThat(dao.volumes()).isEmpty()
        assertThat(sync.status.value.ready).isTrue()
    }

    @Test
    fun withoutTheAudioPermissionNothingIsSynced() = runBlocking<Unit> {
        mayRead = false
        index.volume().rows = listOf(row(1))

        assertThat(sync.sync()).isFalse()

        assertThat(dao.files()).isEmpty()
        assertThat(dao.volumes()).isEmpty()
        assertThat(index.rowReads).isZero()
    }

    @Test
    fun theLibraryIsReadyAfterTheFirstSyncAndEachChangeRaisesTheRevision() = runBlocking<Unit> {
        assertThat(sync.status.value.ready).isFalse()
        index.volume().rows = listOf(row(1))

        sync.sync()
        val first = sync.status.value
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()

        assertThat(first.ready).isTrue()
        assertThat(sync.status.value.revision).isGreaterThan(first.revision)
    }

    @Test
    fun aLaterProcessIsReadyBeforeItsFirstSyncEnds() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        sync.close()

        // The next process: a new sync on the same database, whose first sync is held.
        val gate = CountDownLatch(1)
        index.gate = gate
        val later = newSync()
        later.request()

        withTimeout(5.seconds) { later.status.first { it.ready } }
        assertThat(gate.count).isEqualTo(1)
        gate.countDown()
        later.awaitIdle()
    }

    @Test
    fun requestsDuringASyncStartOneMoreSyncAfterIt() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        val gate = CountDownLatch(1)
        index.gate = gate

        sync.request()
        waitFor { index.rowReads == 1 }
        repeat(3) { sync.request() }
        gate.countDown()
        sync.awaitIdle()

        assertThat(index.rowReads).isEqualTo(2)
    }

    @Test
    fun aBurstOfChangeReportsStartsOneSyncASecondAfterTheLast() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))

        // The burst lasts longer than the delay, so a sync a second after the first report would show here.
        repeat(5) {
            sync.requestSoon()
            Thread.sleep(400)
        }
        assertThat(index.rowReads).isZero()
        waitFor { index.rowReads == 1 }
        sync.awaitIdle()
        Thread.sleep(1_500)

        assertThat(index.rowReads).isEqualTo(1)
        assertThat(files().keys).containsExactly(1L)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(20)
        }
    }
}
