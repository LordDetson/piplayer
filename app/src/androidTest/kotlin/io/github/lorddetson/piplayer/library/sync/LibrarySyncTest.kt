package io.github.lorddetson.piplayer.library.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.StoredPlace
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.values.TrackTags
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

    private val rules = FakeRules()

    private fun newSync() = LibrarySync(database, index, mayRead = { mayRead }, rules).also { syncs += it }

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
    fun aChangeOfTheFolderRulesIsAppliedToEveryFileByTheNextSync() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Rock/"), row(2, path = "Music/Pop/"))
        sync.sync()
        val revision = sync.status.value.revision
        assertThat(dao.files().map { it.folderRule }).containsOnly(FolderRuleState.NONE.stored)

        rules.set(FolderRule(PRIMARY, "Music/Rock/", include = false), FolderRule(PRIMARY, "Music/Pop/", include = true))

        assertThat(sync.sync()).isTrue()
        assertThat(sync.status.value.revision).isGreaterThan(revision)
        val byId = files()
        assertThat(byId.getValue(1).folderRule).isEqualTo(FolderRuleState.EXCLUDE.stored)
        assertThat(byId.getValue(2).folderRule).isEqualTo(FolderRuleState.INCLUDE.stored)
        // The same rules again: nothing to apply.
        assertThat(sync.sync()).isFalse()
    }

    @Test
    fun aNewOrMovedFileTakesTheRuleOfItsFolderWhenTheRulesStayTheSame() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Rock/"), row(2, path = "Music/Pop/"))
        rules.set(FolderRule(PRIMARY, "Music/Rock/", include = false))
        sync.sync()
        sync.close()

        // The next process, with the rules unchanged: a file added under the excluded folder and one moved into it.
        index.volume().rows = listOf(row(1, path = "Music/Rock/"), row(2, path = "Music/Rock/", generation = 10), row(3, path = "Music/Rock/Live/"))
        val later = newSync()
        assertThat(later.sync()).isTrue()

        assertThat(files().mapValues { it.value.folderRule }).containsExactlyInAnyOrderEntriesOf(
            mapOf(1L to FolderRuleState.EXCLUDE.stored, 2L to FolderRuleState.EXCLUDE.stored, 3L to FolderRuleState.EXCLUDE.stored),
        )
    }

    @Test
    fun theMirroredRulesOutliveTheProcess() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Rock/"))
        rules.set(FolderRule(PRIMARY, "Music/Rock/", include = false))
        sync.sync()
        sync.close()

        // The next process finds the rules mirrored: nothing to apply.
        assertThat(newSync().sync()).isFalse()

        rules.set()
        assertThat(newSync().sync()).isTrue()
        assertThat(files().getValue(1).folderRule).isEqualTo(FolderRuleState.NONE.stored)
    }

    @Test
    fun aRecordingIsTheIndexsFlagOrOnAndroid11ARecordingsFolder() = runBlocking<Unit> {
        index.volume().rows = listOf(
            row(1, path = "Music/", kinds = MediaKinds(recording = true)),
            row(2, path = "Recordings/", kinds = MediaKinds(recording = null)),
            row(3, path = "Recordings/", kinds = MediaKinds(recording = false)),
        )

        sync.sync()

        assertThat(files().mapValues { it.value.recording }).containsExactlyInAnyOrderEntriesOf(mapOf(1L to true, 2L to true, 3L to false))
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

    /** What the tag pass stores for a file. */
    private suspend fun readByThePass(mediaStoreId: Long, tags: Map<String, List<String>>) {
        val fileId = files().getValue(mediaStoreId).id
        dao.addTrack(TrackEntity.of(fileId, TrackTags.from(tags)))
        dao.markRead(
            fileId, at = 1_000, readerVersion = 1, format = "mpeg", codec = "mp3", sampleRate = 44_100, bitDepth = null,
            channels = 2, bitrate = 320, tagEncoding = "windows-1251", coverPresent = true, coverBytes = 5, tagLengthMs = 7,
        )
        TrackDisplayWriter(database).refresh(listOf(fileId))
    }

    private fun display(mediaStoreId: Long) = runBlocking {
        val fileId = dao.files().single { it.mediaStoreId == mediaStoreId }.id
        dao.displays().single { it.fileId == fileId }
    }

    private val tagged = mapOf("TITLE" to listOf("Tag title"), "ARTIST" to listOf("Tag artist"), "ALBUM" to listOf("Tag album"))

    @Test
    fun whatThePassReadShowsOnceItIsStored() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, title = "Index title"))
        sync.sync()

        readByThePass(1, tagged)

        with(display(1)) {
            assertThat(title).isEqualTo("Tag title")
            assertThat(artist).isEqualTo("Tag artist")
            assertThat(album).isEqualTo("Tag album")
            assertThat(titleKey).isEqualTo("tag title")
            assertThat(missingInfoReasons).isNotNull()
        }
    }

    @Test
    fun aFileStoredAgainWithTheSameSizeAndTimeKeepsWhatThePassRead() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, title = "Index title", name = "old.mp3", generation = 1))
        sync.sync()
        readByThePass(1, tagged)

        index.volume().rows = listOf(row(1, title = "New index title", name = "new.mp3", generation = 2))
        assertThat(sync.sync()).isTrue()

        with(files().getValue(1)) {
            assertThat(displayName).isEqualTo("new.mp3")
            assertThat(tagPassAt).isEqualTo(1_000)
            assertThat(readerVersion).isEqualTo(1)
            assertThat(format).isEqualTo("mpeg")
            assertThat(codec).isEqualTo("mp3")
            assertThat(sampleRate).isEqualTo(44_100)
            assertThat(channels).isEqualTo(2)
            assertThat(bitrate).isEqualTo(320)
            assertThat(tagEncoding).isEqualTo("windows-1251")
            assertThat(embeddedCoverPresent).isTrue()
            assertThat(embeddedCoverBytes).isEqualTo(5)
            assertThat(tagLengthMs).isEqualTo(7)
        }
        assertThat(dao.tracks()).hasSize(1)
        with(display(1)) {
            assertThat(title).isEqualTo("Tag title")
            assertThat(searchText).contains("tag title").contains("new").doesNotContain("old").doesNotContain("index")
        }
    }

    @Test
    fun aFileWithoutATitleTagTakesTheNewNameWhenItIsRenamed() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "Artist - Old.mp3", generation = 1))
        sync.sync()
        readByThePass(1, emptyMap())
        assertThat(display(1).title).isEqualTo("Old")

        index.volume().rows = listOf(row(1, name = "Artist - New.mp3", generation = 2))
        sync.sync()

        assertThat(display(1).title).isEqualTo("New")
    }

    @Test
    fun aChangedFileForgetsWhatThePassRead() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, title = "Index title", generation = 1))
        sync.sync()
        readByThePass(1, tagged)
        val replaced = row(1, title = "Index title", generation = 2)

        index.volume().rows = listOf(replaced.copy(size = replaced.size + 1))
        sync.sync()

        with(files().getValue(1)) {
            assertThat(tagPassAt).isNull()
            assertThat(readerVersion).isNull()
            assertThat(format).isNull()
            assertThat(embeddedCoverPresent).isNull()
            assertThat(tagLengthMs).isNull()
        }
        assertThat(dao.tracks()).isEmpty()
        with(display(1)) {
            assertThat(title).isEqualTo("Index title")
            assertThat(missingInfoReasons).isNull()
        }
    }

    @Test
    fun anIndexRebuildKeepsWhatThePassRead() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, title = "Index title", name = "a.mp3"))
        sync.sync()
        readByThePass(1, tagged)

        // The same file under a new index ID and a new version of the index.
        index.volumes.getValue(PRIMARY).version = "2"
        index.volume(version = "2").rows = listOf(row(7, title = "Index title", name = "a.mp3").copy(size = 1_001, modifiedSeconds = 1_700_000_001))
        sync.sync()

        assertThat(files().getValue(7).tagPassAt).isEqualTo(1_000)
        assertThat(dao.tracks()).hasSize(1)
        assertThat(display(7).title).isEqualTo("Tag title")
    }

    @Test
    fun aRemovedFileLeavesNothingForTheNextFileOfItsId() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        readByThePass(2, tagged)
        val removedId = files().getValue(2).id

        index.volume().rows = listOf(row(1))
        sync.sync()
        assertThat(dao.tracks()).isEmpty()

        // A new file never takes the removed file's ID (docs/library.md §8.4).
        index.volume().rows = listOf(row(1), row(3, title = "Third", generation = 3))
        sync.sync()

        assertThat(files().getValue(3).id).isGreaterThan(removedId)
        assertThat(files().getValue(3).tagPassAt).isNull()
        assertThat(display(3).title).isEqualTo("Third")
    }

    @Test
    fun aNewDisplayVersionDerivesTheDisplaysAgainFromTheStoredValues() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, title = "Index one"), row(2, title = "Index two"))
        sync.sync()
        readByThePass(1, tagged)
        database.withWriteTransaction {
            usePrepared("UPDATE track_display SET title = 'stale', title_key = 'stale'") { it.step() }
        }
        dao.setDisplayVersion(TrackDisplayWriter.VERSION - 1)
        val revision = sync.status.value.revision

        assertThat(sync.sync()).isTrue()

        assertThat(display(1).title).isEqualTo("Tag title")
        assertThat(display(2).title).isEqualTo("Index two")
        assertThat(dao.displayVersion()).isEqualTo(TrackDisplayWriter.VERSION)
        assertThat(sync.status.value.revision).isGreaterThan(revision)
    }

    @Test
    fun aFirstSyncRecordsTheDisplayVersionAndALaterOneChangesNothing() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        assertThat(dao.displayVersion()).isEqualTo(TrackDisplayWriter.VERSION)

        assertThat(sync.sync()).isFalse()
    }

    @Test
    fun mirroringTheRulesKeepsTheDisplayVersion() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()

        rules.set(FolderRule(PRIMARY, "Music/", include = false))
        sync.sync()

        assertThat(dao.displayVersion()).isEqualTo(TrackDisplayWriter.VERSION)
    }

    @Test
    fun theDatabaseIsWrittenAheadOfTheLogSoListsReadDuringAWrite() = runBlocking<Unit> {
        val file = java.io.File(context.cacheDir, "journal-mode-test.db")
        val onDisk = LibraryDatabase.open(context, file)
        try {
            val mode = onDisk.useReaderConnection { connection ->
                connection.usePrepared("PRAGMA journal_mode") { it.step(); it.getText(0) }
            }
            assertThat(mode).isEqualToIgnoringCase("wal")
        } finally {
            onDisk.close()
            file.delete()
            java.io.File("${file.path}-wal").delete()
            java.io.File("${file.path}-shm").delete()
        }
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
        assertThat(sync.status.value.known).describedAs("the library knows its folders").isFalse()
    }

    @Test
    fun theLibraryKnowsItsFoldersOnlyOnceASyncHasSucceeded() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        index.failingVolume = FakeMediaIndex.PRIMARY
        index.failure = IllegalStateException("The index went away")
        sync.sync()
        assertThat(sync.status.value.known).isFalse()

        index.failure = null
        sync.sync()

        assertThat(sync.status.value.known).isTrue()
    }

    @Test
    fun aDeviceWithoutVolumesIsKnownAfterItsFirstSync() = runBlocking<Unit> {
        sync.sync()

        assertThat(sync.status.value.known).isTrue()
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
        assertThat(later.status.value.known).describedAs("the folders of the earlier sync are known").isTrue()
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

    @Test
    fun aDeletedFilesIdIsNeverGivenToAnotherFile() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        val second = files().getValue(2).id

        index.volume().rows = listOf(row(1))
        sync.sync()
        index.volume().rows = listOf(row(1), row(3, generation = 10))
        sync.sync()

        assertThat(files().getValue(3).id).isGreaterThan(second)
    }

    @Test
    fun theLibraryHasAnIdOfItsOwnThatStays() = runBlocking<Unit> {
        val first = dao.libraryId()
        index.volume().rows = listOf(row(1))
        sync.sync()

        assertThat(first).isNotBlank()
        assertThat(dao.libraryId()).isEqualTo(first)
        val other = LibraryDatabase.open(context, file = null)
        try {
            assertThat(other.library().libraryId()).isNotEqualTo(first)
        } finally {
            other.close()
        }
    }

    @Test
    fun aSeededFirstSyncGivesFilesAtTheSamePlaceTheirOldIds() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"), row(3, name = "new.mp3"))
        index.volume("external_sd").rows = listOf(row(4, volume = "external_sd", name = "c.mp3"))
        val seed = listOf(
            StoredPlace(fileId = 40, volume = PRIMARY, relativePath = "Music/", displayName = "a.mp3"),
            StoredPlace(fileId = 7, volume = PRIMARY, relativePath = "Music/", displayName = "b.mp3"),
            StoredPlace(fileId = 90, volume = PRIMARY, relativePath = "Music/", displayName = "gone.mp3"),
            StoredPlace(fileId = 12, volume = "external_sd", relativePath = "Music/", displayName = "c.mp3"),
        )
        val seeded = LibrarySync(database, index, mayRead = { true }, rules, seed = seed).also { syncs += it }

        seeded.sync()

        val files = files()
        assertThat(files.getValue(1).id).isEqualTo(40)
        assertThat(files.getValue(2).id).isEqualTo(7)
        assertThat(files.getValue(4).id).isEqualTo(12)
        // A new file never takes an ID of the seed, also not that of a file that is gone.
        assertThat(files.getValue(3).id).isGreaterThan(90)
        assertThat(dao.displays().map { it.fileId }).containsExactlyInAnyOrder(40, 7, 12, files.getValue(3).id)
    }
}
