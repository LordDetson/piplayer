package io.github.lorddetson.piplayer.library.tagpass

import android.net.Uri
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
import io.github.lorddetson.piplayer.nativelib.taglib.TagReadError
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.TaggerTesting
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The content key and lengths in the library: what the pass stores and what the walks settle (docs/library.md §7). */
@RunWith(AndroidJUnit4::class)
class DurationWalksTest {

    private val context = TestFiles.targetContext
    private val database = LibraryDatabase.open(context, file = null)
    private val dao = database.library()
    private val index = FakeMediaIndex()
    private val rules = FakeRules()
    private val sync = LibrarySync(database, index, mayRead = { true }, rules)
    private val gate = PassGate().apply { setUiVisible(true) }
    private val batches = CopyOnWriteArrayList<List<SongRow>>()
    private val asked = CopyOnWriteArrayList<Long>()
    private val key = "ab".repeat(32)

    private var answer: suspend (PassFile, DurationPlan) -> WalkAnswer = { _, _ -> WalkAnswer.Measured(WALKED) }

    private val walks = DurationWalks(
        database, sync,
        DurationWalker { file, plan ->
            asked += file.mediaStoreId
            answer(file, plan)
        },
        gate, grace = 10.milliseconds, crashBackoff = 1.milliseconds, onBatch = { batches += it },
    )

    @After
    fun close() {
        sync.close()
        database.close()
    }

    private fun report(plan: DurationPlan, key: String = this.key, error: TagReadError? = null) = TagReport(
        error = error,
        properties = if (error == null) mapOf("TITLE" to listOf("Tag")) else emptyMap(),
        audio = TagReport.Audio(99_999, 128, 44_100, 2).takeIf { error == null },
        content = TagReport.Content(key, "mp3", plan),
    )

    /** The library holds one file per plan, read by the pass; index lengths are [INDEX] ms. */
    private suspend fun read(vararg plans: DurationPlan) {
        index.volume().rows = plans.indices.map { row(it + 1L, name = "$it.mp3", durationMs = INDEX) }
        sync.sync()
        pass(plans.toList()).run()
    }

    private fun pass(plans: List<DurationPlan>, readerVersion: Int = 1) = TagPass(
        database, sync,
        { file -> PassRead.Replied(report(plans[file.mediaStoreId.toInt() - 1])) },
        cyrillicLanguage = { false },
        readerVersion = readerVersion,
        crashBackoff = 1.milliseconds,
    )

    private suspend fun fileOf(mediaStoreId: Long) = dao.files().single { it.mediaStoreId == mediaStoreId }

    private suspend fun lengthShown(mediaStoreId: Long) =
        dao.displays().single { it.fileId == fileOf(mediaStoreId).id }.durationMs

    private val walk = DurationPlan(null, DurationCheck.WALK)
    private val flacEnd = DurationPlan(180_000, DurationCheck.FLAC_END)
    private val header = DurationPlan(5_000, DurationCheck.NONE)

    @Test
    fun theKeyAndTheHeaderLengthAreStoredWithTheTagsAndShown() = runBlocking<Unit> {
        read(header)

        with(fileOf(1)) {
            assertThat(contentKey).isEqualTo(key)
            assertThat(durationMs).isEqualTo(5_000)
            assertThat(durationSource).isEqualTo(DurationSource.HEADER)
        }
        assertThat(lengthShown(1)).isEqualTo(5_000)
        assertThat(dao.tracks().single().trackKey).isEqualTo("$key:0")
    }

    @Test
    fun aFileWaitingForAWalkShowsTheIndexLengthAndNotTagLibsEstimate() = runBlocking<Unit> {
        read(walk)

        with(fileOf(1)) {
            assertThat(durationMs).isNull()
            assertThat(durationSource).isEqualTo(DurationSource.PENDING_WALK)
        }
        assertThat(lengthShown(1)).isEqualTo(INDEX)
    }

    @Test
    fun aFileTagLibDoesNotKnowStillGetsItsKeyAndAWalk() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "a.amr", durationMs = INDEX))
        sync.sync()
        TagPass(
            database, sync,
            { PassRead.Replied(report(walk, error = TagReadError.UNSUPPORTED_FORMAT)) },
            cyrillicLanguage = { false },
        ).run()

        assertThat(fileOf(1).contentKey).isEqualTo(key)

        walks.run()

        assertThat(fileOf(1).durationMs).isEqualTo(WALKED)
        assertThat(lengthShown(1)).isEqualTo(WALKED)
    }

    @Test
    fun aWalkSettlesTheLengthEverywhereAndComesOnce() = runBlocking<Unit> {
        read(walk)

        assertThat(walks.run()).isEqualTo(1)

        with(fileOf(1)) {
            assertThat(durationMs).isEqualTo(WALKED)
            assertThat(durationSource).isEqualTo(DurationSource.WALK)
        }
        assertThat(lengthShown(1)).isEqualTo(WALKED)
        assertThat(batches.flatten().single().durationMs).isEqualTo(WALKED)

        assertThat(walks.run()).isEqualTo(0)
        assertThat(asked).hasSize(1)
    }

    @Test
    fun filesTheHeaderSettlesAreNotWalked() = runBlocking<Unit> {
        read(header)

        assertThat(walks.run()).isEqualTo(0)
        assertThat(asked).isEmpty()
    }

    @Test
    fun theCheckOfFlacsEndGetsTheHeaderLengthAndConfirmsIt() = runBlocking<Unit> {
        read(flacEnd)
        assertThat(lengthShown(1)).isEqualTo(180_000)
        answer = { _, plan ->
            assertThat(plan).isEqualTo(flacEnd)
            WalkAnswer.Measured(180_000)
        }

        walks.run()

        assertThat(fileOf(1).durationSource).isEqualTo(DurationSource.HEADER)
        assertThat(walks.run()).isEqualTo(0)
    }

    @Test
    fun aWalkThatFindsNothingKeepsTheIndexLengthAndIsNotRepeated() = runBlocking<Unit> {
        read(walk)
        answer = { _, _ -> WalkAnswer.Measured(null) }

        walks.run()

        assertThat(fileOf(1).durationSource).isEqualTo(DurationSource.UNKNOWN)
        assertThat(lengthShown(1)).isEqualTo(INDEX)
        walks.run()
        assertThat(asked).hasSize(1)
    }

    @Test
    fun aFileChangedDuringItsWalkKeepsNothingOfIt() = runBlocking<Unit> {
        read(walk)
        answer = { _, _ ->
            index.volume().rows = listOf(row(1, name = "0.mp3", durationMs = INDEX, generation = 100).copy(size = 99))
            sync.sync()
            WalkAnswer.Measured(WALKED)
        }

        walks.run()

        assertThat(fileOf(1).durationMs).isNull()
        assertThat(lengthShown(1)).isEqualTo(INDEX)
    }

    @Test
    fun aFileThatChangesIsMeasuredAgain() = runBlocking<Unit> {
        read(walk)
        walks.run()

        index.volume().rows = listOf(row(1, name = "0.mp3", durationMs = INDEX, generation = 100).copy(size = 99))
        sync.sync()

        with(fileOf(1)) {
            assertThat(durationMs).isNull()
            assertThat(durationSource).isNull()
            assertThat(contentKey).isNull()
        }
        assertThat(lengthShown(1)).isEqualTo(INDEX)
    }

    @Test
    fun aFullReadKeepsWhatAWalkSettled() = runBlocking<Unit> {
        read(walk)
        walks.run()

        pass(listOf(walk), readerVersion = 2).run()

        assertThat(fileOf(1).durationMs).isEqualTo(WALKED)
        assertThat(walks.run()).isEqualTo(0)
    }

    @Test
    fun aCrashSkipsTheFileUntilAskedAgainAndTheOthersGoOn() = runBlocking<Unit> {
        read(walk, walk)
        answer = { file, _ -> if (file.mediaStoreId == 1L) WalkAnswer.Crashed else WalkAnswer.Measured(WALKED) }

        assertThat(walks.run()).isEqualTo(1)
        assertThat(fileOf(1).durationSource).isEqualTo(DurationSource.PENDING_WALK)
        assertThat(fileOf(2).durationMs).isEqualTo(WALKED)

        assertThat(walks.run()).isEqualTo(0)
        assertThat(asked.count { it == 1L }).isEqualTo(1)

        walks.forgetFailures()
        answer = { _, _ -> WalkAnswer.Measured(WALKED) }
        assertThat(walks.run()).isEqualTo(1)
    }

    @Test
    fun anUnreachableTaggerSaysNothingAboutTheFile() = runBlocking<Unit> {
        read(walk)
        var tries = 0
        answer = { _, _ -> if (++tries < 3) WalkAnswer.Unavailable else WalkAnswer.Measured(WALKED) }

        assertThat(walks.run()).isEqualTo(1)
        assertThat(tries).isEqualTo(3)
    }

    @Test
    fun filesInExcludedFoldersAreNotWalked() = runBlocking<Unit> {
        read(walk)
        rules.set(FolderRule(PRIMARY, "Music/", include = false))
        sync.sync()

        assertThat(walks.run()).isEqualTo(0)
        assertThat(asked).isEmpty()
    }

    @Test
    fun aRunAskedForStopsTheWalksBetweenFilesAndTheNextRunGoesOn() = runBlocking<Unit> {
        read(walk, walk)

        assertThat(walks.run(stopWhen = { asked.isNotEmpty() })).isEqualTo(1)
        assertThat(asked).hasSize(1)

        assertThat(walks.run()).isEqualTo(1)
    }

    @Test
    fun aClosedGateHoldsTheNextWalk() = runBlocking<Unit> {
        read(walk)
        gate.setUiVisible(false)

        val run = async(Dispatchers.Default) { walks.run() }
        kotlinx.coroutines.delay(200)
        assertThat(asked).isEmpty()

        gate.setUiVisible(true)
        assertThat(withTimeout(5.seconds) { run.await() }).isEqualTo(1)
    }

    @Test
    fun aGateClosingEndsAWalkAndTheFileComesAgainAfterTheOthers() = runBlocking<Unit> {
        read(walk, walk)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var first = true
        answer = { _, _ ->
            if (first) {
                first = false
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            WalkAnswer.Measured(WALKED)
        }

        val run = async(Dispatchers.Default) { walks.run() }
        withTimeout(5.seconds) { started.await() }
        gate.setUiVisible(false)
        withTimeout(5.seconds) { cancelled.await() }

        gate.setUiVisible(true)

        assertThat(withTimeout(5.seconds) { run.await() }).isEqualTo(2)
        assertThat(asked).containsExactly(1, 2, 1)
    }

    @Test
    fun aWalkThatFinishesWithinTheGraceIsKept() = runBlocking<Unit> {
        read(walk)
        val slowWalks = DurationWalks(
            database, sync,
            DurationWalker { _, _ ->
                gate.setUiVisible(false)
                kotlinx.coroutines.delay(50)
                WalkAnswer.Measured(WALKED)
            },
            gate, grace = 5.seconds, crashBackoff = 1.milliseconds,
        )

        assertThat(slowWalks.run()).isEqualTo(1)
    }

    @Test
    fun throughTheRealTaggerAFileWithoutAHeaderGetsItsLengthFromAWalk() = runBlocking<Unit> {
        val opened = TaggerTesting.freshDatabase("duration-walks-test.db")
        val real = opened.first
        val client = TaggerTesting.client(real, 30.seconds)
        val realSync = LibrarySync(real, index, mayRead = { true }, FakeRules())
        val mp3 = TestFiles.copy("playback/seek-mp3-cbr-no-header.mp3")
        try {
            index.volume().rows = listOf(row(1, name = "seek.mp3", durationMs = INDEX))
            realSync.sync()
            val reader = TaggerPassReader(context, client, uriOf = { Uri.fromFile(File(mp3.path)) })

            TagPass(real, realSync, reader, cyrillicLanguage = { false }).run()

            val dao = real.library()
            assertThat(dao.files().single().contentKey)
                .isEqualTo("15710f5275b85b3c9e9638c47c12a684d498e23742fe66bb6602b98cebe905de")
            assertThat(dao.displays().single().durationMs).isEqualTo(INDEX)

            assertThat(DurationWalks(real, realSync, reader, gate).run()).isEqualTo(1)

            // The fixture is twelve segments of a second.
            assertThat(dao.displays().single().durationMs).isBetween(11_900, 12_100)
            assertThat(dao.files().single().durationSource).isEqualTo(DurationSource.WALK)
        } finally {
            client.close()
            realSync.close()
            real.close()
        }
    }

    private companion object {
        const val INDEX = 7_000L
        const val WALKED = 12_013L
    }
}
