package io.github.lorddetson.piplayer.library.tagpass

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.SongRow
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.MediaKinds
import io.github.lorddetson.piplayer.library.values.LegacyEncoding
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.library.values.LegacyText
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.tagging.TagReport
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The tag pass over a fake reader and an in-memory library database (docs/library.md §5). */
@RunWith(AndroidJUnit4::class)
class TagPassTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val dao = database.library()
    private val index = FakeMediaIndex()
    private val rules = FakeRules()
    private val sync = LibrarySync(database, index, mayRead = { true }, rules)
    private val batches = CopyOnWriteArrayList<List<SongRow>>()
    private var cyrillic = true
    private var manual: LegacyEncoding? = null
    private val decoded = DecodedEncoding.InMemory()

    /** Answers from [answers] by index ID, else with a title made of the display name. */
    private inner class FakeReader : PassReader {
        val answers = HashMap<Long, PassRead>()
        val reads = AtomicInteger()
        var before: suspend (PassFile) -> Unit = {}

        override suspend fun read(file: PassFile): PassRead {
            reads.incrementAndGet()
            before(file)
            return answers[file.mediaStoreId]
                ?: PassRead.Replied(TagReport(properties = mapOf("TITLE" to listOf("Tag ${file.displayName}"))))
        }
    }

    private val reader = FakeReader()

    private fun pass(readerVersion: Int = 1, batchSize: Int = 200) = TagPass(
        database, sync, reader,
        cyrillicLanguage = { cyrillic },
        legacyEncoding = { manual },
        decodedEncoding = decoded,
        readerVersion = readerVersion,
        batchSize = batchSize,
        crashBackoff = 1.milliseconds,
        onBatch = { batches += it },
    )

    @After
    fun close() {
        sync.close()
        database.close()
    }

    private fun files() = runBlocking { dao.files() }.associateBy { it.mediaStoreId }
    private fun display(mediaStoreId: Long) = runBlocking {
        val id = dao.files().single { it.mediaStoreId == mediaStoreId }.id
        dao.displays().single { it.fileId == id }
    }

    private suspend fun library(vararg ids: Long) {
        index.volume().rows = ids.map { row(it, title = "Index $it", name = "$it.mp3") }
        sync.sync()
    }

    private fun tagged(vararg values: Pair<String, String>, audio: TagReport.Audio? = null) = PassRead.Replied(
        TagReport(
            properties = values.groupBy({ it.first }, { it.second }),
            audio = audio,
            container = "mpeg",
            codec = "mp3",
        ),
    )

    @Test
    fun aRunReadsEveryFileAndListsThenShowItsTags() = runBlocking<Unit> {
        library(*LongArray(1_000) { it + 1L })

        val result = pass().run()

        assertThat(result.read).isEqualTo(1_000)
        assertThat(reader.reads.get()).isEqualTo(1_000)
        assertThat(files().values).allSatisfy { assertThat(it.tagPassAt).isNotNull() }
        assertThat(display(7).title).isEqualTo("Tag 7.mp3")
        assertThat(dao.tracks()).hasSize(1_000)
    }

    @Test
    fun theFileIsStoredWithItsTechnicalDataAndTypedFields() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.Replied(
            TagReport(
                properties = mapOf("TITLE" to listOf("T"), "TRACKNUMBER" to listOf("3/12"), "COMPOSER" to listOf("C")),
                audio = TagReport.Audio(lengthMs = 61_000, bitrateKbps = 320, sampleRate = 44_100, channels = 2),
                pictures = listOf(TagReport.PictureInfo("Front Cover", "image/png", "", 77)),
                container = "flac",
                codec = "flac",
                bitDepth = 24,
            ),
        )

        pass(readerVersion = 3).run()

        with(files().getValue(1)) {
            assertThat(readerVersion).isEqualTo(3)
            assertThat(format).isEqualTo("flac")
            assertThat(codec).isEqualTo("flac")
            assertThat(bitDepth).isEqualTo(24)
            assertThat(sampleRate).isEqualTo(44_100)
            assertThat(channels).isEqualTo(2)
            assertThat(bitrate).isEqualTo(320)
            assertThat(tagLengthMs).isEqualTo(61_000)
            assertThat(embeddedCoverPresent).isTrue()
            assertThat(embeddedCoverBytes).isEqualTo(77)
            assertThat(tagFailure).isNull()
        }
        with(dao.tracks().single()) {
            assertThat(trackNumber).isEqualTo(3)
            assertThat(trackTotal).isEqualTo(12)
            assertThat(rawTagsJson).contains("TRACKNUMBER")
        }
    }

    @Test
    fun aRunStoppedMidWayLosesAtMostOneBatchAndTheNextDoesNotReadTheCommittedOnesAgain() = runBlocking<Unit> {
        library(*LongArray(25) { it + 1L })
        var count = 0
        reader.before = {
            if (++count == 14) throw CancellationException("The process ended")
        }

        runCatching { pass(batchSize = 5).run() }

        val committed = files().values.count { it.tagPassAt != null }
        assertThat(committed).isEqualTo(10)
        reader.before = {}
        reader.reads.set(0)
        val second = pass(batchSize = 5).run()
        assertThat(second.read).isEqualTo(15)
        assertThat(reader.reads.get()).isEqualTo(15)
        assertThat(files().values).allSatisfy { assertThat(it.tagPassAt).isNotNull() }
    }

    @Test
    fun aSecondRunReadsNothing() = runBlocking<Unit> {
        library(1, 2)
        pass().run()
        reader.reads.set(0)

        pass().run()

        assertThat(reader.reads.get()).isZero()
    }

    @Test
    fun aNewReaderVersionReadsEveryFileAgainAndListsKeepTheTagsMeanwhile() = runBlocking<Unit> {
        library(1)
        pass(readerVersion = 1).run()
        reader.answers[1] = tagged("TITLE" to "Newer")
        reader.before = { assertThat(display(1).title).isEqualTo("Tag 1.mp3") }

        pass(readerVersion = 2).run()

        assertThat(display(1).title).isEqualTo("Newer")
        assertThat(dao.tracks()).hasSize(1)
    }

    @Test
    fun aFileTagLibCannotReadKeepsTheIndexValuesAndIsNotTriedAgainUntilItChanges() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.Replied(TagReport(error = TagReadError.UNSUPPORTED_FORMAT))

        val first = pass().run()

        assertThat(first.failed).isEqualTo(1)
        with(files().getValue(1)) {
            assertThat(tagPassAt).isNull()
            assertThat(tagFailure).isEqualTo("unsupported")
            assertThat(readerVersion).isEqualTo(1)
        }
        assertThat(display(1).title).isEqualTo("Index 1")
        reader.reads.set(0)
        pass().run()
        assertThat(reader.reads.get()).isZero()

        val replaced = row(1, title = "Index 1", name = "1.mp3", generation = 5)
        index.volume().rows = listOf(replaced.copy(size = replaced.size + 1))
        sync.sync()
        pass().run()
        assertThat(reader.reads.get()).isEqualTo(1)
    }

    @Test
    fun aReadErrorIsAFailureOfItsOwnAndNotTriedAgainUntilItChanges() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.Replied(TagReport(error = TagReadError.NATIVE_ERROR))

        pass().run()

        assertThat(files().getValue(1).tagFailure).isEqualTo("read")
        reader.reads.set(0)
        pass().run()
        assertThat(reader.reads.get()).isZero()
    }

    @Test
    fun aFileThatCannotBeOpenedIsTriedInALaterProcessNotInThisOne() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.OpenFailed
        val pass = pass()

        pass.run()
        assertThat(files().getValue(1).tagFailure).isEqualTo("open")
        assertThat(files().getValue(1).readerVersion).isNull()

        reader.reads.set(0)
        pass.run()
        assertThat(reader.reads.get()).isZero()

        reader.answers.remove(1)
        pass().run()
        assertThat(reader.reads.get()).isEqualTo(1)
        assertThat(files().getValue(1).tagFailure).isNull()
    }

    @Test
    fun readingEverythingAgainAlsoTriesWhatFailedInThisProcess() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.OpenFailed
        val pass = pass()
        pass.run()
        reader.answers.remove(1)
        reader.reads.set(0)

        pass.forgetFailures()
        pass.run()

        assertThat(reader.reads.get()).isEqualTo(1)
        assertThat(files().getValue(1).tagPassAt).isNotNull()
    }

    @Test
    fun aReaderThatCannotBeReachedBlamesNoFileAndTheRunGoesOnAfterAWhile() = runBlocking<Unit> {
        library(1)
        var calls = 0
        val answers = reader.answers
        reader.before = { if (++calls == 1) answers[1] = PassRead.Unavailable else answers.remove(1) }

        val result = pass().run()

        assertThat(calls).isEqualTo(2)
        assertThat(result.read).isEqualTo(1)
        assertThat(result.failed).isZero()
        assertThat(files().getValue(1).tagFailure).isNull()
        assertThat(files().getValue(1).tagPassAt).isNotNull()
    }

    @Test
    fun aReadErrorOfTheDeviceIsTriedInALaterProcessNotKeptAsTheFilesFault() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.Replied(TagReport(error = TagReadError.IO_ERROR))
        val pass = pass()

        pass.run()
        assertThat(files().getValue(1).tagFailure).isEqualTo("open")
        assertThat(files().getValue(1).readerVersion).isNull()
        reader.reads.set(0)
        pass.run()
        assertThat(reader.reads.get()).isZero()

        reader.answers.remove(1)
        pass().run()
        assertThat(files().getValue(1).tagPassAt).isNotNull()
    }

    @Test
    fun filesRemovedByASyncTakeTheirAlbumsArtistsAndGenresWithThemAlsoWhenNothingIsLeftToRead() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to "t", "ALBUM" to "One", "ARTISTS" to "Kino", "GENRE" to "Rock")
        pass().run()
        assertThat(dao.albums()).hasSize(1)

        index.volume().rows = emptyList()
        sync.sync()
        pass().run()

        assertThat(dao.albums()).isEmpty()
        assertThat(dao.artists()).isEmpty()
        assertThat(dao.genres()).isEmpty()
    }

    @Test
    fun aNewSpellingReachesTheAlbumAndTheArtist() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("ALBUM" to "one", "ALBUMARTIST" to "band", "ARTISTS" to "the kino")
        pass(readerVersion = 1).run()

        reader.answers[1] = tagged("ALBUM" to "One", "ALBUMARTIST" to "Band", "ARTISTS" to "The Kino", "ARTISTSORT" to "Kino, The")
        pass(readerVersion = 2).run()

        assertThat(dao.albums().single().title).isEqualTo("One")
        assertThat(dao.albums().single().albumArtist).isEqualTo("Band")
        val artist = dao.artists().single { it.name.lowercase() == "the kino" }
        assertThat(artist.name).isEqualTo("The Kino")
        assertThat(artist.sortName).isEqualTo("Kino, The")
    }

    @Test
    fun aCrashIsRecordedAndTheRunGoesOn() = runBlocking<Unit> {
        library(1, 2)
        reader.answers[1] = PassRead.Crashed

        val result = pass().run()

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.read).isEqualTo(1)
        assertThat(files().getValue(1).tagFailure).isEqualTo("crash")
        assertThat(files().getValue(1).readerVersion).isNull()
        assertThat(files().getValue(2).tagPassAt).isNotNull()
    }

    @Test
    fun aFileMarkedUnsafeIsNotOffered() = runBlocking<Unit> {
        library(1, 2)
        dao.setUnsafeCount(files().getValue(1).id, 2)

        pass().run()

        assertThat(reader.reads.get()).isEqualTo(1)
        assertThat(files().getValue(1).tagPassAt).isNull()
    }

    @Test
    fun aFileReplacedDuringItsReadKeepsNothingOfTheOldRead() = runBlocking<Unit> {
        library(1)
        val old = row(1, title = "Index 1", name = "1.mp3", generation = 5)
        reader.before = {
            index.volume().rows = listOf(old.copy(size = old.size + 1))
            sync.sync()
        }

        pass().run()

        assertThat(files().getValue(1).tagPassAt).isNull()
        assertThat(dao.tracks()).isEmpty()
        assertThat(display(1).title).isEqualTo("Index 1")
    }

    @Test
    fun aRenameDuringAReadKeepsTheNewNameInTheDisplay() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = PassRead.Replied(TagReport(properties = emptyMap()))
        reader.before = {
            index.volume().rows = listOf(row(1, title = "Index 1", name = "Band - Renamed.mp3", generation = 5))
            sync.sync()
        }

        pass().run()

        assertThat(display(1).title).isEqualTo("Renamed")
        assertThat(display(1).searchText).contains("renamed")
    }

    @Test
    fun aFileRemovedDuringItsReadStaysRemoved() = runBlocking<Unit> {
        library(1, 2)
        reader.before = {
            if (index.volume().rows.size == 2) {
                index.volume().rows = emptyList()
                sync.sync()
            }
        }

        pass().run()

        assertThat(dao.files()).isEmpty()
        assertThat(dao.tracks()).isEmpty()
        assertThat(dao.displays()).isEmpty()
    }

    @Test
    fun aFileInAnExcludedFolderIsNotRead() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/In/"), row(2, path = "Music/Out/"))
        rules.set(FolderRule(PRIMARY, "Music/Out/", include = false))
        sync.sync()

        pass().run()

        assertThat(reader.reads.get()).isEqualTo(1)
        assertThat(files().getValue(2).tagPassAt).isNull()
    }

    @Test
    fun visibleSongsAreReadInListOrderAndHiddenKindsLast() = runBlocking<Unit> {
        index.volume().rows = listOf(
            row(1, title = "Zebra"),
            row(2, title = "Apple", kinds = MediaKinds(music = false, ringtone = true)),
            row(3, title = "Mango"),
        )
        sync.sync()
        val order = mutableListOf<Long>()
        reader.before = { order += it.mediaStoreId }

        pass().run()

        assertThat(order).containsExactly(3L, 1L, 2L)
    }

    @Test
    fun aBatchIsPublishedWithItsNewValuesAfterItIsCommitted() = runBlocking<Unit> {
        library(1, 2, 3)

        pass(batchSize = 2).run()

        assertThat(batches.map { it.size }).containsExactly(2, 1)
        assertThat(batches.flatten().map { it.title }).contains("Tag 1.mp3", "Tag 2.mp3", "Tag 3.mp3")
    }

    @Test
    fun aRunDoesNotMoveTheRevision() = runBlocking<Unit> {
        library(1, 2)
        val before = sync.status.value.revision

        pass().run()

        assertThat(sync.status.value.revision).isEqualTo(before)
    }

    @Test
    fun theProgressCountsEveryFileAndEnds() = runBlocking<Unit> {
        library(1, 2, 3)
        reader.answers[2] = PassRead.OpenFailed
        val pass = pass(batchSize = 1)
        val seen = CopyOnWriteArrayList<PassProgress>()
        reader.before = { seen += pass.progress.value }

        pass.run()

        assertThat(seen.first().total).isEqualTo(3)
        assertThat(pass.progress.value.running).isFalse()
        assertThat(pass.progress.value.done).isEqualTo(3)
    }

    @Test
    fun legacyTextIsDecodedForTheFileAndTheChoiceStored() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))

        pass().run()

        assertThat(display(1).title).isEqualTo("Кино")
        assertThat(files().getValue(1).tagEncoding).isEqualTo(LegacyText.WINDOWS_1251)
        // The raw values stay as read, so that the manual choice of M2b can decode them again without a read.
        assertThat(dao.tracks().single().rawTagsJson).contains(markedCp1251("Кино"))
    }

    @Test
    fun withoutACyrillicLanguageLegacyTextIsLatin1() = runBlocking<Unit> {
        library(1)
        cyrillic = false
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))

        pass().run()

        assertThat(files().getValue(1).tagEncoding).isEqualTo(LegacyText.LATIN_1)
    }

    @Test
    fun aHugeValueStaysOutOfTheRawTagsButTheFileIsRead() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to "Short", "LYRICS" to "x".repeat(100_000))

        pass().run()

        assertThat(display(1).title).isEqualTo("Short")
        assertThat(dao.tracks().single().rawTagsJson!!.length).isLessThan(1_000)
    }

    @Test
    fun albumsArtistsAndGenresAreFilledAndOrphansRemoved() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/A/"), row(2, path = "Music/A/CD2/"), row(3, path = "Music/B/"))
        sync.sync()
        fun tags(album: String, artist: String) = tagged(
            "TITLE" to "t", "ALBUM" to album, "ARTISTS" to artist, "ALBUMARTIST" to "Band", "GENRE" to "Rock", "COMPOSER" to "Writer",
        )
        reader.answers[1] = tags("One", "Kino")
        reader.answers[2] = tags("One", "kino")
        reader.answers[3] = tags("Two", "DDT")

        pass().run()

        assertThat(dao.albums().map { it.title }).containsExactlyInAnyOrder("One", "Two")
        assertThat(dao.tracks().map { it.albumId }.toSet()).hasSize(2)
        assertThat(dao.artists().map { it.name.lowercase() }).containsExactlyInAnyOrder("kino", "ddt", "band", "writer")
        assertThat(dao.genres().map { it.name }).containsExactly("Rock")
        assertThat(dao.trackGenres()).hasSize(3)
        assertThat(dao.trackArtists().map { it.role }.toSet()).hasSize(3)

        // Reading the third file again under another album leaves "Two" and "DDT" without tracks.
        reader.answers[3] = tags("Three", "Kino")
        pass(readerVersion = 2).run()

        assertThat(dao.albums().map { it.title }).containsExactlyInAnyOrder("One", "Three")
        assertThat(dao.artists().map { it.name.lowercase() }).doesNotContain("ddt")
    }

    @Test
    fun aFileMovedToAnotherFolderIsReadAgainSoThatItsAlbumFollows() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/A/"))
        sync.sync()
        reader.answers[1] = tagged("TITLE" to "t", "ALBUM" to "One")
        pass().run()
        val before = dao.tracks().single().albumId

        index.volume().rows = listOf(row(1, path = "Music/B/", generation = 5))
        sync.sync()
        assertThat(files().getValue(1).tagPassAt).isNotNull()
        reader.reads.set(0)
        pass().run()

        assertThat(reader.reads.get()).isEqualTo(1)
        assertThat(dao.tracks().single().albumId).isNotEqualTo(before)
        assertThat(dao.albums()).hasSize(1)
    }

    private fun encoding(name: String) = LegacyEncodings.ALL.single { it.name == name }

    /** [text] as a legacy field holds it in [charset], read back in [shownIn]. */
    private fun misread(text: String, charset: String, shownIn: String) =
        String(text.toByteArray(Charset.forName(charset)), Charset.forName(shownIn))

    /** Decodes the stored files again with the setting now, as a change of it asks (docs/library.md §5.7). */
    private suspend fun redecode(pass: TagPass = pass()) {
        pass.askRedecode()
        pass.run()
    }

    @Test
    fun aManualChoiceDecodesNewReadsInThatCodePage() = runBlocking<Unit> {
        library(1)
        manual = encoding("koi8-r")
        reader.answers[1] = tagged("TITLE" to marked("Кино", "KOI8-R"))

        pass().run()

        assertThat(display(1).title).isEqualTo("Кино")
        assertThat(files().getValue(1).tagEncoding).isEqualTo("koi8-r")
    }

    @Test
    fun aChangedChoiceDecodesOnlyTheLegacyFilesAgainAndReadsNoFile() = runBlocking<Unit> {
        library(1, 2, 3)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))
        reader.answers[2] = tagged("TITLE" to "Юникод")
        reader.answers[3] = tagged("TITLE" to "Ascii")
        pass().run()
        val tracksBefore = dao.tracks().associate { it.fileId to it.id }
        batches.clear()
        reader.reads.set(0)

        manual = encoding("koi8-r")
        redecode()

        assertThat(reader.reads.get()).describedAs("files read").isZero()
        assertThat(display(1).title).isEqualTo(misread("Кино", "windows-1251", "KOI8-R"))
        assertThat(files().getValue(1).tagEncoding).isEqualTo("koi8-r")
        assertThat(display(2).title).isEqualTo("Юникод")
        assertThat(display(3).title).isEqualTo("Ascii")
        val id1 = files().getValue(1).id
        val tracksAfter = dao.tracks().associate { it.fileId to it.id }
        assertThat(tracksAfter.filterKeys { it != id1 }).describedAs("the other tracks stay as they were")
            .isEqualTo(tracksBefore.filterKeys { it != id1 })
        assertThat(batches.flatten().map { it.fileId }).describedAs("rows handed to the lists").containsExactly(id1)
        assertThat(batches.flatten().single().title).isEqualTo(misread("Кино", "windows-1251", "KOI8-R"))
    }

    @Test
    fun aWrongChoiceIsUndoneByAutomatic() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"), "ARTIST" to markedCp1251("Цой"))
        pass().run()
        assertThat(display(1).title).isEqualTo("Кино")

        manual = encoding("shift_jis")
        redecode()
        assertThat(display(1).title).isNotEqualTo("Кино")
        assertThat(files().getValue(1).tagEncoding).isEqualTo("shift_jis")

        manual = null
        redecode()
        assertThat(display(1).title).isEqualTo("Кино")
        assertThat(display(1).artist).isEqualTo("Цой")
        assertThat(files().getValue(1).tagEncoding).isEqualTo(LegacyText.WINDOWS_1251)
        assertThat(reader.reads.get()).isEqualTo(1)
    }

    @Test
    fun filesWhoseCodePageStaysAreNotTouched() = runBlocking<Unit> {
        library(1, 2)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))
        reader.answers[2] = tagged("TITLE" to marked("Сплин", "UTF-8"))
        pass().run()
        assertThat(files().getValue(2).tagEncoding).isEqualTo(LegacyText.UTF_8)
        val tracksBefore = dao.tracks().map { it.id }
        batches.clear()

        // The same code page the automatic mode found, and valid UTF-8, which a manual choice keeps.
        manual = encoding("windows-1251")
        redecode()

        assertThat(dao.tracks().map { it.id }).isEqualTo(tracksBefore)
        assertThat(batches.flatten()).isEmpty()
        assertThat(display(2).title).isEqualTo("Сплин")
    }

    @Test
    fun albumsArtistsAndGenresFollowTheTextDecodedAgain() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/A/"))
        sync.sync()
        reader.answers[1] = tagged(
            "TITLE" to markedCp1251("Кино"), "ALBUM" to markedCp1251("Чёрный альбом"), "ARTIST" to markedCp1251("Цой"),
            "GENRE" to markedCp1251("Рок"),
        )
        pass().run()
        assertThat(dao.albums().map { it.title }).containsExactly("Чёрный альбом")

        manual = encoding("koi8-r")
        redecode()

        assertThat(dao.albums().map { it.title }).containsExactly(misread("Чёрный альбом", "windows-1251", "KOI8-R"))
        assertThat(dao.artists().map { it.name }).containsExactly(misread("Цой", "windows-1251", "KOI8-R"))
        assertThat(dao.genres().map { it.name }).containsExactly(misread("Рок", "windows-1251", "KOI8-R"))
        assertThat(dao.tracks().single().albumId).isEqualTo(dao.albums().single().id)

        manual = null
        redecode()

        assertThat(dao.albums().map { it.title }).containsExactly("Чёрный альбом")
        assertThat(dao.artists().map { it.name }).containsExactly("Цой")
        assertThat(dao.genres().map { it.name }).containsExactly("Рок")
    }

    @Test
    fun aChoiceMadeWhileARunReadsAppliesToTheFilesItHasStoredBeforeItsNextFile() = runBlocking<Unit> {
        library(1, 2, 3, 4)
        for (id in 1L..4L) reader.answers[id] = tagged("TITLE" to markedCp1251("Кино"))
        val pass = pass(batchSize = 1)
        val storedBeforeFile3 = mutableListOf<String?>()
        reader.before = { file ->
            when (file.mediaStoreId) {
                2L -> {
                    manual = encoding("koi8-r")
                    pass.askRedecode()
                }
                3L -> storedBeforeFile3 += listOf(1L, 2L).map { id -> dao.files().single { it.mediaStoreId == id }.tagEncoding }
            }
        }

        pass.run()

        assertThat(storedBeforeFile3).describedAs("decoded again before the next file").containsExactly("koi8-r", "koi8-r")
        assertThat(reader.reads.get()).isEqualTo(4)
        for (id in 1L..4L) assertThat(files().getValue(id).tagEncoding).describedAs("file $id").isEqualTo("koi8-r")
    }

    @Test
    fun aChoiceMadeDuringTheLastReadIsAppliedBeforeTheRunEnds() = runBlocking<Unit> {
        library(1, 2, 3)
        for (id in 1L..3L) reader.answers[id] = tagged("TITLE" to markedCp1251("Кино"))
        val pass = pass(batchSize = 1)
        reader.before = { file ->
            if (file.mediaStoreId == 3L) {
                manual = encoding("koi8-r")
                pass.askRedecode()
            }
        }

        pass.run()

        assertThat(reader.reads.get()).isEqualTo(3)
        for (id in 1L..3L) {
            assertThat(files().getValue(id).tagEncoding).describedAs("file $id").isEqualTo("koi8-r")
            assertThat(display(id).title).describedAs("file $id").isEqualTo(misread("Кино", "windows-1251", "KOI8-R"))
        }
    }

    @Test
    fun aRunWithTheChoiceTheFilesWereDecodedWithTouchesNothing() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))
        manual = encoding("koi8-r")
        redecode()
        val tracksBefore = dao.tracks().map { it.id }
        batches.clear()

        pass().run()

        assertThat(dao.tracks().map { it.id }).isEqualTo(tracksBefore)
        assertThat(batches).isEmpty()
    }

    @Test
    fun aChangeNoRunCouldApplyBeforeTheProcessEndedIsAppliedByTheNextRun() = runBlocking<Unit> {
        library(1)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))
        pass().run()
        assertThat(decoded.read()).isNull()

        // The setting changed, but no run decoded the files before the process ended: nothing asks the new run.
        manual = encoding("koi8-r")
        pass().run()

        assertThat(display(1).title).isEqualTo(misread("Кино", "windows-1251", "KOI8-R"))
        assertThat(decoded.read()).isEqualTo("koi8-r")
        assertThat(reader.reads.get()).isEqualTo(1)

        manual = null
        pass().run()

        assertThat(display(1).title).isEqualTo("Кино")
        assertThat(decoded.read()).isNull()
    }

    @Test
    fun aDecodingThatFailsHalfWayIsFinishedByTheNextRun() = runBlocking<Unit> {
        library(1, 2)
        reader.answers[1] = tagged("TITLE" to markedCp1251("Кино"))
        reader.answers[2] = tagged("TITLE" to markedCp1251("Кино"))
        pass().run()
        manual = encoding("koi8-r")
        var failed = false
        val failing = TagPass(
            database, sync, reader, cyrillicLanguage = { cyrillic }, legacyEncoding = { manual }, decodedEncoding = decoded,
            batchSize = 1,
            onBatch = {
                if (!failed) {
                    failed = true
                    throw CancellationException("The process ended")
                }
            },
        )

        runCatching { redecode(failing) }

        assertThat(decoded.read()).describedAs("not marked as decoded").isNull()
        pass().run()
        for (id in 1L..2L) assertThat(display(id).title).isEqualTo(misread("Кино", "windows-1251", "KOI8-R"))
        assertThat(decoded.read()).isEqualTo("koi8-r")
    }

    private fun marked(text: String, charset: String) = buildString {
        for (byte in text.toByteArray(Charset.forName(charset))) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
        }
    }

    private fun markedCp1251(text: String) = buildString {
        for (byte in text.toByteArray(Charset.forName("windows-1251"))) {
            val value = byte.toInt() and 0xFF
            append(if (value < 0x80) value.toChar() else (LegacyText.MARK_BASE + value).toChar())
        }
    }
}
