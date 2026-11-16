package io.github.lorddetson.piplayer.library.mirror

import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackDisplayEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decided values on their way into the library database (TI §7.1, §10.3; §11.2 M3 "Decided values in the library"):
 * a fake media index, the real databases, the real sync, attach pass and tag pass over a fake reader.
 */
@RunWith(AndroidJUnit4::class)
class DecisionMirrorTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val attacher = IdentityAttacher(library, user, sync, holders = { user.user().identitiesHoldingData().toSet() })
    private val mirror = DecisionMirror(library, user, sync)
    private val repository = Decisions(user)
    private val decisions = LibraryDecisions(repository) { mirror }

    @After
    fun close() {
        mirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
    }

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun display(mediaStoreId: Long): TrackDisplayEntity {
        val id = fileId(mediaStoreId)
        return library.library().displays().single { it.fileId == id }
    }

    /** Syncs [rows], gives each file the key of its media ID (or [keys]) and attaches identities. */
    private suspend fun library(rows: List<IndexRow>, keys: Map<Long, Long> = emptyMap()) {
        index.volume().rows = rows
        sync.sync()
        for (row in rows) {
            val k = keys[row.mediaStoreId] ?: row.mediaStoreId
            library.library().setContent(fileId(row.mediaStoreId), key(k), 200_000, DurationSource.HEADER)
        }
        attacher.attach()
    }

    private suspend fun identityOf(n: Long) = checkNotNull(user.identities().idOfKey(Hex.bytes(key(n))))

    @Test
    fun aDecisionReachesTheDisplayOfEveryFileOfItsKeyWithItsSearchTextReasonsAndBadge() = runBlocking<Unit> {
        // Files 2 and 3 are byte-identical copies.
        library(listOf(row(1), row(2), row(3)), keys = mapOf(3L to 2L))
        val version = mirror.version.value

        val applied = checkNotNull(decisions.apply(request(key(2), details(title = "Ёжик в тумане", album = "Ночной ёж"))))

        for (copy in listOf(2L, 3L)) {
            with(display(copy)) {
                assertThat(title).isEqualTo("Ёжик в тумане")
                assertThat(artist).isEqualTo("Ann")
                assertThat(album).isEqualTo("Ночной ёж")
                assertThat(trackNumber).isEqualTo(3)
                assertThat(searchText).contains("тумане", "ann").doesNotContain("song")
                assertThat(badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
            }
        }
        // The other file keeps its own values.
        assertThat(display(1).title).isEqualTo("Song 1")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_NONE)
        val rows = library.decided().valuesOf(listOf(fileId(2)))
        assertThat(rows.single { it.field == DecidedField.TITLE.stored })
            .isEqualTo(DecidedMirrorEntity(fileId(2), "title", "Ёжик в тумане", DecisionKind.LEVEL_IDENTIFICATION, applied.decisionId))
        assertThat(rows.map { it.field }).contains(DecidedField.RECORDING_MBID.stored, DecidedField.LABEL.stored)
        assertThat(mirror.version.value).isGreaterThan(version)
        // A search finds the decided title.
        assertThat(LibraryLists(library).songs(io.github.lorddetson.piplayer.library.lists.SongQuery.SONGS.copy(search = "тумане")).map { it.id })
            .containsExactlyInAnyOrder(fileId(2), fileId(3))
    }

    @Test
    fun aDecidedRecordingIdClearsItsReasonOnceTheFileIsRead() = runBlocking<Unit> {
        library(listOf(row(1)))
        readByThePass(1)
        assertThat(display(1).missingInfoReasons!! and MergedValues.NO_RECORDING_ID).isNotZero()

        decisions.apply(request(key(1)))

        val reasons = display(1).missingInfoReasons!!
        assertThat(reasons and MergedValues.NO_RECORDING_ID).isZero()
        assertThat(reasons and MergedValues.TITLE_FROM_FILE_NAME).isZero()
    }

    @Test
    fun undoTakesTheDecidedValuesBack() = runBlocking<Unit> {
        library(listOf(row(1)))
        val applied = checkNotNull(decisions.apply(request(key(1))))
        assertThat(display(1).title).isEqualTo("Song")

        decisions.undo(applied.decisionId)

        assertThat(display(1).title).isEqualTo("Song 1")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_NONE)
        assertThat(library.decided().valuesOf(listOf(fileId(1)))).isEmpty()
    }

    @Test
    fun anEditOverAnIdentificationShowsAsEditedAndItsUndoBringsTheIdentificationBack() = runBlocking<Unit> {
        library(listOf(row(1)))
        decisions.apply(request(key(1)))
        val edit = checkNotNull(decisions.edit(identityOf(1), mapOf(DecidedField.TITLE to "Mine")))
        assertThat(display(1).title).isEqualTo("Mine")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_EDITED)

        decisions.undo(edit.decisionId)

        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
    }

    @Test
    fun theSyncKeepsTheDecidedValues() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        decisions.apply(request(key(1)))

        // A change of another file: an incremental sync.
        index.volume().rows = listOf(row(1), row(2, title = "Renamed", generation = 50))
        sync.sync()
        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(2).title).isEqualTo("Renamed")

        // A new index version: every file is stored again.
        index.volume().version = "2"
        index.volume().rows = listOf(row(1, generation = 60), row(2, title = "Renamed", generation = 60))
        sync.sync()
        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(1).album).isEqualTo("Record")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
    }

    @Test
    fun aNewDisplayVersionKeepsTheDecidedValues() = runBlocking<Unit> {
        library(listOf(row(1)))
        decisions.apply(request(key(1)))

        library.library().setDisplayVersion(0)
        sync.sync()

        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
    }

    @Test
    fun aRefreshByTheWriterKeepsTheDecidedValues() = runBlocking<Unit> {
        library(listOf(row(1)))
        decisions.apply(request(key(1)))

        TrackDisplayWriter(library).refresh(listOf(fileId(1)))

        assertThat(display(1).title).isEqualTo("Song")
    }

    /** What the reader of [readByThePass] answers: tags with a title, and the file's key by its media ID. */
    private inner class Reader : PassReader {
        override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
            TagReport(
                properties = mapOf("TITLE" to listOf("Tag ${file.mediaStoreId}"), "ARTIST" to listOf("Tag artist")),
                audio = TagReport.Audio(200_000, 128, 44_100, 2),
                content = TagReport.Content(key(file.mediaStoreId), "mp3", DurationPlan(200_000, DurationCheck.NONE)),
            ),
        )
    }

    /** A run of the real tag pass, whose batches ask the mirror for their files as the app's do. */
    private suspend fun readByThePass(vararg mediaStoreIds: Long) {
        TagPass(
            library, sync, Reader(),
            cyrillicLanguage = { false },
            legacyEncoding = { null },
            decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onBatch = { rows -> mirror.requestFiles(rows.map { it.fileId }) },
        ).run()
        mirror.awaitIdle()
    }

    @Test
    fun theTagPassKeepsTheDecidedValuesOfAFileItReadsAgain() = runBlocking<Unit> {
        library(listOf(row(1)))
        readByThePass(1)
        decisions.apply(request(key(1)))
        assertThat(display(1).title).isEqualTo("Song")

        // The file changed (a tagger wrote it): the sync forgets what the pass read, and the pass reads it again.
        index.volume().rows = listOf(row(1).copy(size = 5_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        assertThat(display(1).title).isEqualTo("Song")
        readByThePass(1)

        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(1).artist).isEqualTo("Ann")
    }

    @Test
    fun aFileWhoseKeyComesLaterGetsTheDecidedValuesAfterItsBatch() = runBlocking<Unit> {
        // File 1 is identified; file 2, a copy, has not been read yet.
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        library.library().setContent(fileId(1), key(1), 200_000, DurationSource.HEADER)
        attacher.attach()
        decisions.apply(request(key(1)))
        assertThat(display(2).title).isEqualTo("Song 2")

        // The pass reads file 2, whose key is file 1's.
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = mapOf("TITLE" to listOf("Tag")),
                        audio = TagReport.Audio(200_000, 128, 44_100, 2),
                        content = TagReport.Content(key(1), "mp3", DurationPlan(200_000, DurationCheck.NONE)),
                    ),
                )
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onBatch = { rows -> mirror.requestFiles(rows.map { it.fileId }) },
        ).run()
        mirror.awaitIdle()

        assertThat(display(2).title).isEqualTo("Song")
        assertThat(display(2).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
    }

    @Test
    fun aProcessThatDiedBetweenTheTwoCommitsShowsTheDecisionAfterTheNextStart() = runBlocking<Unit> {
        library(listOf(row(1)))
        mirror.mirrorAll()
        // The user database holds the decision, the library database not: the process died in between.
        val applied = checkNotNull(repository.apply(request(key(1))))
        assertThat(display(1).title).isEqualTo("Song 1")
        assertThat(library.library().mirroredChangeCounter()).isLessThan(applied.counter)

        // The next start compares the counters.
        val next = DecisionMirror(library, user, sync)
        try {
            assertThat(next.mirrorIfChanged()).isTrue()
            assertThat(display(1).title).isEqualTo("Song")
            assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
            // A start whose counters agree writes nothing.
            val version = next.version.value
            assertThat(next.mirrorIfChanged()).isFalse()
            assertThat(next.version.value).isEqualTo(version)
        } finally {
            next.close()
        }
    }

    @Test
    fun aCallerCancelledWhileTheRunAfterItsDecisionWaitsStillGetsItsDecisionMirrored() = runBlocking<Unit> {
        library(listOf(row(1)))
        val identity = identityOf(1)
        // A sync holds the library database, as a first sync or the start-up run does.
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { sync.exclusive { holding.complete(Unit); release.await() } }
        holding.await()

        // The review screen applies from its own scope, and the user leaves it once the decision is stored.
        val caller = launch(Dispatchers.IO) { decisions.apply(request(key(1))) }
        withTimeout(10_000) { while (repository.decidedOf(listOf(identity)).isEmpty()) delay(10) }
        caller.cancelAndJoin()
        release.complete(Unit)
        holder.join()
        mirror.awaitIdle()

        assertThat(display(1).title).isEqualTo("Song")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
    }

    @Test
    fun aRunThatFailsAfterTheCommitReturnsTheDecisionAndAsksForAWholeRun() = runBlocking<Unit> {
        library(listOf(row(1)))
        // The first look of the mirror fails, as a full disk or a closed database would make its transaction fail.
        val failures = AtomicInteger(1)
        val flaky = DecisionMirror(library, user, sync, ready = {
            if (failures.getAndDecrement() > 0) throw IllegalStateException("The library database cannot be written") else true
        })
        try {
            val committed = LibraryDecisions(repository) { flaky }.apply(request(key(1)))

            assertThat(committed).isNotNull()
            assertThat(repository.decidedOf(listOf(identityOf(1)))).isNotEmpty()
            // The session repairs itself: the whole run it asked for brings the library level.
            flaky.awaitIdle()
            assertThat(display(1).title).isEqualTo("Song")
            assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
        } finally {
            flaky.close()
        }
    }

    @Test
    fun aStartMakesOneWholeRunWhetherTheCountersDifferOrAgree() = runBlocking<Unit> {
        library(listOf(row(1)))
        mirror.mirrorAll()
        // The counters differ: a decision committed without its run, as when the process died in between.
        repository.apply(request(key(1)))
        val apart = DecisionMirror(library, user, sync)
        try {
            // The start-up run, then the first attach pass of the process, which wrote no identities.
            apart.requestIfChanged()
            apart.awaitIdle()
            apart.requestUnlessRan()
            apart.awaitIdle()

            assertThat(apart.wholeRuns).isEqualTo(1)
            assertThat(display(1).title).isEqualTo("Song")
        } finally {
            apart.close()
        }

        // The counters agree: the run after the first attach pass is the start's one whole run.
        val level = DecisionMirror(library, user, sync)
        try {
            level.requestIfChanged()
            level.awaitIdle()
            assertThat(level.wholeRuns).isEqualTo(0)
            level.requestUnlessRan()
            level.awaitIdle()
            assertThat(level.wholeRuns).isEqualTo(1)
            level.requestUnlessRan()
            level.awaitIdle()
            assertThat(level.wholeRuns).isEqualTo(1)
            // A pass that wrote identities asks for a whole run whatever ran before.
            level.request()
            level.awaitIdle()
            assertThat(level.wholeRuns).isEqualTo(2)
        } finally {
            level.close()
        }
    }

    @Test
    fun aStartUpRunThatFailsLeavesTheWholeRunToTheFirstAttachPass() = runBlocking<Unit> {
        library(listOf(row(1)))
        mirror.mirrorAll()
        // The counters differ: a decision committed without its run, as when the process died in between.
        repository.apply(request(key(1)))
        // The start-up run's write fails, as a full disk or a locked database would make it.
        library.withWriteTransaction {
            usePrepared(
                "CREATE TRIGGER poisoned BEFORE INSERT ON decided_mirror BEGIN SELECT RAISE(ABORT, 'Writing failed'); END",
            ) { it.step() }
        }
        val next = DecisionMirror(library, user, sync)
        try {
            next.requestIfChanged()
            next.awaitIdle()
            assertThat(display(1).title).isEqualTo("Song 1")
            assertThat(next.wholeRuns).isEqualTo(0)
            assertThat(next.changedRuns).isEqualTo(0)
            library.withWriteTransaction { usePrepared("DROP TRIGGER poisoned") { it.step() } }

            // The first attach pass of the process, which wrote no identities, still gets the start its whole run.
            next.requestUnlessRan()
            next.awaitIdle()

            assertThat(display(1).title).isEqualTo("Song")
            assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
            assertThat(next.wholeRuns).isEqualTo(1)
        } finally {
            next.close()
        }
    }

    @Test
    fun theRunsOfACounterCheckAreCountedApart() = runBlocking<Unit> {
        library(listOf(row(1)))
        mirror.mirrorAll()
        repository.apply(request(key(1)))
        val next = DecisionMirror(library, user, sync)
        try {
            // A whole run asked for without the check does not count as one of its runs.
            next.request()
            next.awaitIdle()
            assertThat(next.wholeRuns).isEqualTo(1)
            assertThat(next.changedRuns).isEqualTo(0)
            repository.apply(request(key(1), details(title = "Again")))

            next.requestIfChanged()
            next.awaitIdle()
            assertThat(next.wholeRuns).isEqualTo(2)
            assertThat(next.changedRuns).isEqualTo(1)
            // Counters that agree make no run.
            next.requestIfChanged()
            next.awaitIdle()
            assertThat(next.changedRuns).isEqualTo(1)
            assertThat(display(1).title).isEqualTo("Again")
        } finally {
            next.close()
        }
    }

    @Test
    fun aDecisionsOwnRunMovesTheMirroredCounterOnlyFromOneBelow() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        mirror.mirrorAll()
        val start = library.library().mirroredChangeCounter()

        val first = checkNotNull(decisions.apply(request(key(1))))
        assertThat(first.counter).isEqualTo(start + 1)
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(first.counter)

        // A favorite raises the counter without a decision: the next decision's run cannot tell what lies between.
        io.github.lorddetson.piplayer.userdata.Favorites(user).set(identityOf(2), favorite = true)
        val second = checkNotNull(decisions.apply(request(key(2))))
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(first.counter)
        assertThat(display(2).title).isEqualTo("Song")

        // The whole run brings it level.
        assertThat(mirror.mirrorIfChanged()).isTrue()
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(second.counter)
    }

    @Test
    fun aWholeRunWritesOnlyTheFilesWhoseDecidedValuesDiffer() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        decisions.apply(request(key(1)))
        val version = mirror.version.value

        assertThat(mirror.mirrorAll()).isFalse()
        assertThat(mirror.version.value).isEqualTo(version)

        // A value changed behind the mirror's back is put right.
        library.decided().saveValues(listOf(DecidedMirrorEntity(fileId(2), "title", "Stray", 2, 999)))
        assertThat(mirror.mirrorAll()).isTrue()
        assertThat(library.decided().valuesOf(listOf(fileId(2)))).isEmpty()
        assertThat(display(1).title).isEqualTo("Song")
    }

    @Test
    fun aTakeoverMovesTheDecidedValuesWithTheKey() = runBlocking<Unit> {
        library(listOf(row(1)))
        val identity = identityOf(1)
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE)))

        // The identity's key moves to another file, as re-attachment does.
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        val moved = user.identities().identity(identity)!!.copy(contentKey = Hex.bytes(key(2)))
        user.identities().updateAll(listOf(moved))
        library.library().setContent(fileId(2), key(2), 200_000, DurationSource.HEADER)
        mirror.mirrorAll()

        assertThat(display(2).title).isEqualTo("Song")
        assertThat(display(1).title).isEqualTo("Song 1")
    }
}
