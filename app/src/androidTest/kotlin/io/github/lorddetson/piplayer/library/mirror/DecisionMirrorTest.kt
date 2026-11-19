package io.github.lorddetson.piplayer.library.mirror

import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.DecidedMirrorDao
import io.github.lorddetson.piplayer.library.db.DecidedMirrorEntity
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackDisplayEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.MissingInfoDecision
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
import io.github.lorddetson.piplayer.library.values.DecisionContent
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.library.values.MergedValues
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.tagging.write.tagPropertiesHash
import io.github.lorddetson.piplayer.userdata.AppliedChangeEntity
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TagBackupEntity
import io.github.lorddetson.piplayer.userdata.TagWriteJournal
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Whether the tag pass runs, as the mirror asks for a run made only for its batches; and how often it was taken so. */
    private val passRunning = AtomicBoolean(false)
    private val takenInPass = AtomicInteger()
    private val mirror = DecisionMirror(library, user, sync, inPass = { passRunning.get().also { if (it) takenInPass.incrementAndGet() } })
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

    /** A run of the real tag pass, whose batches report what they stored to the mirror as the app's do. */
    private suspend fun readByThePass(vararg mediaStoreIds: Long) {
        TagPass(
            library, sync, Reader(),
            cyrillicLanguage = { false },
            legacyEncoding = { null },
            decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onStored = { read, failed -> mirror.requestRead(read, failed) },
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
            onStored = { read, failed -> mirror.requestRead(read, failed) },
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
    fun aWholeRunPutsRightADecidedFileWhoseRowsChangedBehindItsBack() = runBlocking<Unit> {
        library(listOf(row(1), row(2), row(3), row(4), row(5)))
        for (n in 1L..5L) decisions.apply(request(key(n), details(title = "Decided $n", recording = n.toInt())))
        val ids = (1L..5L).associateWith { fileId(it) }
        val mirrored = library.decided().valuesOf(ids.values.toList())
        fun title(n: Long) = mirrored.single { it.fileId == ids.getValue(n) && it.field == "title" }

        // A value, a level and a decision changed, a part gone, and a part this build does not know.
        library.decided().saveValues(
            listOf(title(1).copy(value = "Stray"), title(2).copy(level = DecisionKind.LEVEL_USER_EDIT), title(3).copy(decisionId = 999)),
        )
        library.decided().saveValues(listOf(DecidedMirrorEntity(ids.getValue(5), "mood", "Calm", 2, title(5).decisionId)))
        library.withWriteTransaction {
            usePrepared("DELETE FROM decided_mirror WHERE file_id = ${ids.getValue(4)} AND field = 'artist'") { it.step() }
        }
        assertThat(mirror.mirrorAll()).isTrue()

        assertThat(library.decided().valuesOf(ids.values.toList())).containsExactlyInAnyOrderElementsOf(mirrored)
        for (n in 1L..5L) assertThat(display(n).title).isEqualTo("Decided $n")
        assertThat(mirror.mirrorAll()).isFalse()
    }

    @Test
    fun aWholeRunComparesAgainTheFilesWhoseKeysMovedWhileItWaitedForTheSync() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE)))
        assertThat(display(1).title).isEqualTo("Song")

        // The run compares before it holds off the sync; at that moment the tag pass finds file 1 changed and file 2 a
        // copy of the decided song.
        mirror.beforeHold = {
            sync.exclusive {
                library.library().setContent(fileId(1), key(3), 200_000, DurationSource.HEADER)
                library.library().setContent(fileId(2), key(1), 200_000, DurationSource.HEADER)
            }
        }

        assertThat(mirror.mirrorAll()).isTrue()
        assertThat(display(2).title).isEqualTo("Song")
        assertThat(display(1).title).isEqualTo("Song 1")
        assertThat(library.decided().valuesOf(listOf(fileId(1)))).isEmpty()
    }

    @Test
    fun aWholeRunWritesNothingForAFileTheSyncDeletedWhileItWaited() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE)))
        val first = fileId(1)
        // File 2 still holds the rows of a decision undone before the mirror ran.
        library.decided().saveValues(listOf(DecidedMirrorEntity(fileId(2), "title", "Undone", DecisionKind.LEVEL_IDENTIFICATION, 999)))
        val version = mirror.version.value

        // The run compares before it holds off the sync; at that moment the sync finds file 2 gone and deletes it.
        mirror.beforeHold = {
            index.volume().rows = listOf(row(1))
            sync.sync()
        }

        assertThat(mirror.mirrorAll()).isFalse()
        assertThat(mirror.version.value).isEqualTo(version)
        assertThat(library.decided().all().map { it.fileId }.distinct()).containsExactly(first)
        assertThat(display(1).title).isEqualTo("Song")
    }

    @Test
    fun aWholeRunLeavesAReaderOfTheLibraryDatabaseFreeWhileItReadsTheMirroredRows() = runBlocking<Unit> {
        // On a database file, as in the app: an in-memory database has a single connection.
        val file = File(context.cacheDir, "decision-mirror-readers.db")
        deleteDatabase(file)
        val onDisk = LibraryDatabase.open(context, file)
        val diskSync = LibrarySync(onDisk, FakeMediaIndex(), mayRead = { true }, FakeRules())
        val diskMirror = DecisionMirror(onDisk, user, diskSync)
        val release = CompletableDeferred<Unit>()
        try {
            // Rows of undone decisions, spread over enough row IDs for every range.
            onDisk.decided().saveValues((1L..200L).map { DecidedMirrorEntity(it, "title", "Undone $it", DecisionKind.LEVEL_IDENTIFICATION, it) })
            val entered = AtomicInteger()
            diskMirror.onScanPart = {
                entered.incrementAndGet()
                release.await()
            }
            val run = async(Dispatchers.IO) { diskMirror.mirrorAll() }
            withTimeout(10_000) { while (entered.get() < DecisionMirror.SCAN_PARTS) delay(10) }

            // Every range holds its reader connection, and the lists still read. Not cancelled when it waits: Room keeps
            // a cancelled wait for a reader, so the ranges are let go first.
            val read = async(Dispatchers.IO) {
                onDisk.useReaderConnection { connection ->
                    connection.usePrepared("SELECT count(*) FROM decided_mirror") { it.step(); it.getLong(0) }
                }
            }
            val rows = withTimeoutOrNull(5_000) { read.await() }
            release.complete(Unit)
            assertThat(rows).describedAs("rows read while every range holds its reader").isEqualTo(200L)

            assertThat(run.await()).isTrue()
            assertThat(onDisk.decided().all()).isEmpty()
        } finally {
            release.complete(Unit)
            diskMirror.closeAndJoin()
            diskSync.close()
            onDisk.close()
            deleteDatabase(file)
        }
    }

    private fun deleteDatabase(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }

    /** The size and modification time (seconds) of the file of [mediaStoreId], as the library keeps them. */
    private suspend fun stampOf(mediaStoreId: Long): Pair<Long, Long> {
        val file = library.library().files().single { it.mediaStoreId == mediaStoreId }
        return file.size to file.mtime
    }

    private suspend fun skip(mediaStoreId: Long, keyOf: Long = mediaStoreId): Decisions.Committed {
        val (size, mtime) = stampOf(mediaStoreId)
        return checkNotNull(decisions.skip(identityOf(keyOf), size, mtime))
    }

    private suspend fun lapsedAt(decisionId: Long) = user.decisions().decision(decisionId)?.lapsedAt

    @Test
    fun keepAsIsAndSkipReachTheDisplayOfEveryFileOfTheirKeyAndUndoTakesThemBack() = runBlocking<Unit> {
        // Files 2 and 3 are byte-identical copies.
        library(listOf(row(1), row(2), row(3)), keys = mapOf(3L to 2L))
        val version = mirror.version.value

        val kept = checkNotNull(decisions.keepAsIs(identityOf(2)))

        for (copy in listOf(2L, 3L)) assertThat(display(copy).missingInfoDecision).isEqualTo(MissingInfoDecision.KEEP_AS_IS)
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
        // No value is decided: the display keeps the file's values and badge.
        assertThat(display(2).title).isEqualTo("Song 2")
        assertThat(display(2).badgeState).isEqualTo(DecisionResolver.BADGE_NONE)
        assertThat(library.decided().valuesOf(listOf(fileId(2))))
            .containsExactly(DecidedMirrorEntity(fileId(2), MissingInfoDecision.FIELD, MissingInfoDecision.KEEP_AS_IS_VALUE, 0, kept.decisionId))
        assertThat(mirror.version.value).isGreaterThan(version)
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())

        val skipped = skip(2)
        for (copy in listOf(2L, 3L)) assertThat(display(copy).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)

        decisions.undo(skipped.decisionId)
        for (copy in listOf(2L, 3L)) assertThat(display(copy).missingInfoDecision).isEqualTo(MissingInfoDecision.KEEP_AS_IS)
        decisions.undo(kept.decisionId)
        for (copy in listOf(2L, 3L)) assertThat(display(copy).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
        assertThat(library.decided().valuesOf(listOf(fileId(2), fileId(3)))).isEmpty()
    }

    @Test
    fun keepAsIsAndSkipStayThroughTheSyncANewDisplayVersionTheWriterAndAWholeRun() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        skip(1)
        decisions.keepAsIs(identityOf(2))
        decisions.apply(request(key(2), fields = setOf(ProtectedField.TITLE)))
        suspend fun check(step: String) {
            assertThat(display(1).missingInfoDecision).describedAs(step).isEqualTo(MissingInfoDecision.SKIPPED)
            assertThat(display(2).missingInfoDecision).describedAs(step).isEqualTo(MissingInfoDecision.KEEP_AS_IS)
            assertThat(display(2).title).describedAs(step).isEqualTo("Song")
        }
        check("after the decisions")

        index.volume().version = "2"
        index.volume().rows = listOf(row(1, generation = 60), row(2, generation = 60))
        sync.sync()
        check("after a sync that stores every file again")
        library.library().setDisplayVersion(0)
        sync.sync()
        check("after a new display version")
        TrackDisplayWriter(library).refresh(listOf(fileId(1), fileId(2)))
        check("after a refresh by the writer")
        assertThat(mirror.mirrorAll()).isFalse()

        // A whole run puts back the row lost behind its back.
        library.decided().deleteFiles(listOf(fileId(1)))
        assertThat(mirror.mirrorAll()).isTrue()
        check("after a whole run")
    }

    @Test
    fun aKeepAsIsCommittedWithoutItsRunShowsAfterTheNextStart() = runBlocking<Unit> {
        library(listOf(row(1)))
        mirror.mirrorAll()
        // The process died between the two commits.
        repository.keepAsIs(identityOf(1))
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)

        val next = DecisionMirror(library, user, sync)
        try {
            assertThat(next.mirrorIfChanged()).isTrue()
            assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.KEEP_AS_IS)
            assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
        } finally {
            next.close()
        }
    }

    /** A reader of the tag pass that gives each file the key of [keys] (else its media ID) and the title of [titles]. */
    private inner class StampReader(val keys: Map<Long, Long> = emptyMap(), val titles: Map<Long, String> = emptyMap()) : PassReader {
        override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
            TagReport(
                properties = mapOf(
                    "TITLE" to listOf(titles[file.mediaStoreId] ?: "Tag ${file.mediaStoreId}"), "ARTIST" to listOf("Tag artist"),
                ),
                audio = TagReport.Audio(200_000, 128, 44_100, 2),
                content = TagReport.Content(key(keys[file.mediaStoreId] ?: file.mediaStoreId), "mp3", DurationPlan(200_000, DurationCheck.NONE)),
            ),
        )
    }

    /** A run of the real tag pass that reports what it stored to the mirror, as the app's does. */
    private suspend fun readAndReport(reader: PassReader = StampReader()) {
        pass(reader) { read, failed -> mirror.requestRead(read, failed) }
        mirror.awaitIdle()
    }

    /** A run of the real tag pass with [reader], whose batches report what they stored to [onStored]. */
    private suspend fun pass(reader: PassReader, onStored: (List<Long>, List<Long>) -> Unit) {
        TagPass(
            library, sync, reader,
            cyrillicLanguage = { false },
            legacyEncoding = { null },
            decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onStored = onStored,
        ).run()
    }

    /** The audio of file 1 was encoded again, or a tagger wrote it: a new size and time, so the sync forgets its key. */
    private suspend fun changeFileOne() {
        index.volume().rows = listOf(row(1).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        assertThat(library.library().files().single().contentKey).isNull()
    }

    /** A skipped file that changed, whose rows a whole run then took away: only its place names its Skip (TI §1). */
    private suspend fun skippedThenChangedAndCleared(): Decisions.Committed {
        library(listOf(row(1)))
        readAndReport()
        val skipped = skip(1)
        val file = library.library().files().single()
        val identity = checkNotNull(user.identities().identity(identityOf(1)))
        assertThat(listOf(identity.hintVolumeName, identity.hintRelativePath, identity.hintDisplayName))
            .containsExactly(file.volumeName, file.relativePath, file.displayName)

        changeFileOne()
        mirror.mirrorAll()
        assertThat(library.decided().valuesOf(listOf(fileId(1)))).isEmpty()
        return skipped
    }

    @Test
    fun aChangedSkippedFileWhoseRowsAWholeRunTookAwayIsFoundByItsTracksLocationHint() = runBlocking<Unit> {
        val skipped = skippedThenChangedAndCleared()

        readAndReport(StampReader(keys = mapOf(1L to 9L)))

        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
    }

    @Test
    fun aChangedSkippedFileWhoseReadFailsLetsTheSkipLapseToo() = runBlocking<Unit> {
        val skipped = skippedThenChangedAndCleared()

        readAndReport(
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.OpenFailed
            },
        )

        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
    }

    @Test
    fun aLapseWhoseMirrorRunFailsStaysCommittedAndAsksForAWholeRun() = runBlocking<Unit> {
        library(listOf(row(1)))
        readAndReport()
        val skipped = skip(1)
        changeFileOne()
        val reported = mutableListOf<Pair<List<Long>, List<Long>>>()
        pass(StampReader()) { read, failed -> reported += read to failed }
        // The second look of this mirror, its run after the lapse, fails, as a full disk would make its transaction fail.
        val looks = AtomicInteger()
        val flaky = DecisionMirror(library, user, sync, ready = {
            if (looks.incrementAndGet() == 2) throw IllegalStateException("The library database cannot be written") else true
        })
        try {
            for ((read, failed) in reported) flaky.afterRead(read, failed)

            assertThat(lapsedAt(skipped.decisionId)).isNotNull()
            flaky.awaitIdle()
            assertThat(flaky.wholeRuns).isEqualTo(1)
            assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
            assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
        } finally {
            flaky.close()
        }
    }

    @Test
    fun checksAfterABatchThatFailAreMadeAgainBeforeTheBatchsMirrorRun() = runBlocking<Unit> {
        fileAndCopy()
        // The Skip is made on the copy, a place the track's hint does not name: only the copy's row finds it after its read.
        val skipped = skip(2, keyOf = 1)
        val failures = AtomicInteger(1)
        mirror.retryDelay = 10.milliseconds
        mirror.beforeChecks = { if (failures.getAndDecrement() > 0) throw IllegalStateException("The user database is busy") }

        // The copy's audio was encoded again.
        index.volume().rows = listOf(row(1), row(2).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        readAndReport(StampReader(keys = mapOf(2L to 9L)))

        assertThat(failures.get()).describedAs("checks made again").isNegative()
        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
    }

    /** The copy of [fileAndCopy] encoded again, so that the sync forgets its key and the pass gives it key 9. */
    private suspend fun copyEncodedAgain() {
        index.volume().rows = listOf(row(1), row(2).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
    }

    @Test
    fun aWholeRunAskedForWhileFailedChecksWaitRunsAfterThem() = runBlocking<Unit> {
        fileAndCopy()
        val skipped = skip(2, keyOf = 1)
        val runs = mirror.wholeRuns
        val checks = AtomicInteger()
        // Long enough for the whole run to come first if it did not wait.
        mirror.retryDelay = 1.seconds
        mirror.beforeChecks = {
            if (checks.getAndIncrement() == 0) {
                // As the attach pass after the batch asks for one (TI §10.3), while the checks fail.
                mirror.request()
                throw IllegalStateException("The user database is busy")
            }
        }

        copyEncodedAgain()
        readAndReport(StampReader(keys = mapOf(2L to 9L)))

        assertThat(checks.get()).describedAs("checks made").isEqualTo(2)
        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        assertThat(mirror.wholeRuns).isGreaterThan(runs)
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
    }

    @Test
    fun aWholeRunTakenInOnePassWithABatchWhoseChecksFailWaitsForThemToo() = runBlocking<Unit> {
        fileAndCopy()
        val skipped = skip(2, keyOf = 1)
        val runs = mirror.wholeRuns
        val checks = AtomicInteger()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        mirror.retryDelay = 10.milliseconds
        mirror.beforeChecks = {
            when (checks.getAndIncrement()) {
                // The checks of an earlier batch hold the mirror while the copy's batch and a whole run are asked for,
                // so that its next pass takes both.
                0 -> {
                    holding.complete(Unit)
                    release.await()
                }
                1 -> throw IllegalStateException("The user database is busy")
            }
        }
        mirror.requestRead(emptyList(), failed = listOf(Long.MAX_VALUE))
        withTimeout(10_000) { holding.await() }

        copyEncodedAgain()
        pass(StampReader(keys = mapOf(2L to 9L))) { read, failed -> mirror.requestRead(read, failed) }
        mirror.request()
        release.complete(Unit)
        mirror.awaitIdle()

        assertThat(checks.get()).describedAs("checks made").isEqualTo(3)
        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        assertThat(mirror.wholeRuns).isGreaterThan(runs)
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
    }

    @Test
    fun aRunMadeOnlyForBatchesOfThePassWhileItRunsRaisesTheVersionButNotTheOneTheListsFollow() = runBlocking<Unit> {
        // Each file its own key; a decision on file 2, whose copies the pass finds one after another (docs/library.md §3.5).
        library(listOf(row(1), row(2), row(3), row(4)))
        checkNotNull(decisions.apply(request(key(2), details(title = "Decided"))))
        mirror.awaitIdle()
        fun copyOfTwo(n: Long) = runBlocking { library.library().setContent(fileId(n), key(2), 200_000, DurationSource.HEADER) }
        var version = mirror.version.value
        var listed = mirror.listVersion.value

        // The pass runs and reads a copy: the run waits in the lists and counts as a change of the pass.
        passRunning.set(true)
        copyOfTwo(3)
        mirror.requestRead(listOf(fileId(3)), emptyList())
        mirror.awaitIdle()
        assertThat(display(3).title).isEqualTo("Decided")
        assertThat(mirror.version.value).describedAs("the version of decided values").isGreaterThan(version)
        assertThat(mirror.listVersion.value).describedAs("the version the lists follow").isEqualTo(listed)
        assertThat(takenInPass.get()).describedAs("runs taken as changes of the pass").isEqualTo(1)

        // A run for a batch that changed nothing does not ask.
        mirror.requestRead(listOf(fileId(3)), emptyList())
        mirror.awaitIdle()
        assertThat(takenInPass.get()).isEqualTo(1)

        // A commit during the pass is the user's own change.
        version = mirror.version.value
        checkNotNull(decisions.apply(request(key(2), details(title = "Again", recording = 101))))
        assertThat(mirror.version.value).isGreaterThan(version)
        assertThat(mirror.listVersion.value).describedAs("after a commit during the pass").isGreaterThan(listed)
        listed = mirror.listVersion.value

        // A run that took other files besides a batch's: the mirror is held while both are asked for, so one run takes them.
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        mirror.beforeChecks = {
            if (checks.getAndIncrement() == 0) {
                holding.complete(Unit)
                release.await()
            }
        }
        mirror.requestRead(emptyList(), failed = listOf(Long.MAX_VALUE))
        withTimeout(10_000) { holding.await() }
        copyOfTwo(4)
        mirror.requestRead(listOf(fileId(4)), emptyList())
        mirror.requestFiles(listOf(fileId(1)))
        release.complete(Unit)
        mirror.awaitIdle()
        assertThat(display(4).title).isEqualTo("Again")
        assertThat(mirror.listVersion.value).describedAs("after a run that also took other files").isGreaterThan(listed)
        listed = mirror.listVersion.value
        mirror.beforeChecks = {}

        // The same run once the pass has ended reloads at once, and asks nothing of the pass.
        passRunning.set(false)
        val taken = takenInPass.get()
        copyOfTwo(1)
        mirror.requestRead(listOf(fileId(1)), emptyList())
        mirror.awaitIdle()
        assertThat(display(1).title).isEqualTo("Again")
        assertThat(mirror.listVersion.value).describedAs("after the pass").isGreaterThan(listed)
        assertThat(takenInPass.get()).isEqualTo(taken)
    }

    @Test
    fun checksThatKeepFailingLeaveTheFilesMirroredAfterTheLastTry() = runBlocking<Unit> {
        library(listOf(row(1)))
        readAndReport()
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE)))
        assertThat(display(1).title).isEqualTo("Song")
        val tries = AtomicInteger()
        mirror.retryDelay = 10.milliseconds
        mirror.beforeChecks = {
            tries.incrementAndGet()
            throw IllegalStateException("The user database is busy")
        }

        // The file's key changes: once the checks give up, its run takes the decided title away.
        changeFileOne()
        readAndReport(StampReader(keys = mapOf(1L to 9L)))

        assertThat(tries.get()).isEqualTo(1 + DecisionMirror.CHECK_RETRIES)
        assertThat(display(1).title).isEqualTo("Tag 1")
    }

    @Test
    fun theLapseLooksUpTheFilesThatHoldAStampByTheIndexOfTheKeys() = runBlocking<Unit> {
        library(listOf(row(1)))
        // Exact lookups on every term, so that a library without keys yet, as after a rebuild, is not walked through
        // all of its files that have no key.
        val terms = mapOf(DecidedMirrorDao.STAMPS_WITH_KEYS to "content_key=?", DecidedMirrorDao.UNKEYED_STAMPS to "content_key=? AND size=?")
        assertThat(DecidedMirrorDao.STAMP_QUERIES).containsExactlyInAnyOrderElementsOf(terms.keys)
        for ((sql, term) in terms) {
            val plan = library.useReaderConnection { connection ->
                connection.usePrepared("EXPLAIN QUERY PLAN " + sql.replace(Regex(":\\w+"), "?")) { statement ->
                    buildList { while (statement.step()) add(statement.getText(3)) }
                }
            }
            assertThat(plan).describedAs(sql).singleElement().asString()
                .matches("SEARCH audio_file USING (COVERING )?INDEX \\S+ \\(" + Regex.escape(term) + "\\)")
        }
    }

    /** File 1 and its copy 2, which has the same size and a time of its own, both read by the tag pass. */
    private suspend fun fileAndCopy() {
        library(listOf(row(1), row(2).copy(size = row(1).size)), keys = mapOf(2L to 1L))
        readAndReport(StampReader(keys = mapOf(2L to 1L)))
    }

    @Test
    fun aSkippedFileThatATaggerChangedLetsTheSkipLapseOnceTheTagPassReadsItAgain() = runBlocking<Unit> {
        fileAndCopy()
        val skipped = skip(1)
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)

        // A full read of the library changes no file: the Skip stays.
        sync.exclusive { library.library().forgetReaderVersions() }
        readAndReport(StampReader(keys = mapOf(2L to 1L)))
        assertThat(lapsedAt(skipped.decisionId)).isNull()
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)

        // A tagger writes file 1: its size and time change, the sync forgets what the pass read, and the pass reads it again.
        val counter = user.user().changeCounter()
        index.volume().rows = listOf(row(1).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70), row(2).copy(size = row(1).size))
        sync.sync()
        readAndReport(StampReader(keys = mapOf(2L to 1L)))

        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        assertThat(user.user().changeCounter()).isEqualTo(counter + 1)
        assertThat(library.library().mirroredChangeCounter()).isEqualTo(user.user().changeCounter())
        // The mark is gone from every copy.
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
        assertThat(library.decided().valuesOf(listOf(fileId(1), fileId(2)))).isEmpty()
    }

    @Test
    fun aFileWhoseContentKeyChangedLetsTheSkipLapse() = runBlocking<Unit> {
        library(listOf(row(1)))
        readAndReport()
        val skipped = skip(1)
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)

        // The audio was encoded again: a new size and time, and a key no identity holds yet.
        index.volume().rows = listOf(row(1).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        readAndReport(StampReader(keys = mapOf(1L to 9L)))

        assertThat(lapsedAt(skipped.decisionId)).isNotNull()
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
        // The identity that follows the file to its new key brings no Skip with it.
        val moved = user.identities().identity(identityOf(1))!!.copy(contentKey = Hex.bytes(key(9)))
        user.identities().updateAll(listOf(moved))
        mirror.mirrorAll()
        assertThat(display(1).missingInfoDecision).isEqualTo(MissingInfoDecision.NONE)
    }

    @Test
    fun aCopyThatAppearsKeepsTheSkipAndShowsIt() = runBlocking<Unit> {
        library(listOf(row(1)))
        readAndReport()
        val skipped = skip(1)

        // A byte-identical copy appears, with a time of its own.
        index.volume().rows = listOf(row(1), row(2).copy(size = row(1).size))
        sync.sync()
        readAndReport(StampReader(keys = mapOf(2L to 1L)))

        assertThat(lapsedAt(skipped.decisionId)).isNull()
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)
    }

    @Test
    fun aSkipStaysWhenACopyIsReadBeforeTheSkippedFileHasItsKeyAgain() = runBlocking<Unit> {
        fileAndCopy()
        val skipped = skip(1)
        assertThat(user.decisions().decision(skipped.decisionId)?.fileMtime).isEqualTo(SkipLapse.mtimeMs(stampOf(1).second))

        // As after a rebuild, no file has a key or a read yet; the copy is read, and the skipped file's read fails.
        library.withWriteTransaction {
            usePrepared("UPDATE audio_file SET content_key = NULL, reader_version = NULL, tag_pass_at = NULL") { it.step() }
        }
        readAndReport(
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead =
                    if (file.mediaStoreId == 1L) PassRead.OpenFailed else StampReader(keys = mapOf(2L to 1L)).read(file)
            },
        )

        assertThat(lapsedAt(skipped.decisionId)).isNull()
        // Once the skipped file is read again, unchanged, it holds the stamp with the track's key.
        readAndReport(StampReader(keys = mapOf(2L to 1L)))
        assertThat(lapsedAt(skipped.decisionId)).isNull()
        for (n in listOf(1L, 2L)) assertThat(display(n).missingInfoDecision).isEqualTo(MissingInfoDecision.SKIPPED)
    }

    @Test
    fun aReadOfAFileWhoseDecidedPartChangedInTheFileFlagsItAtItsLocation() = runBlocking<Unit> {
        library(listOf(row(1)))
        readAndReport()
        val file = library.library().files().single()
        val location = Decisions.LocationValues(
            file.volumeName, file.relativePath, file.displayName,
            DecisionContent.ofTags(TrackTags.from(mapOf("TITLE" to listOf("Tag 1"), "ARTIST" to listOf("Tag artist")))),
        )
        decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE, ProtectedField.ARTIST)), atDecision = listOf(location))

        // Read again unchanged: nothing is flagged.
        sync.exclusive { library.library().forgetReaderVersions() }
        readAndReport()
        assertThat(user.decisions().fileStates(identityOf(1))).isEmpty()

        // A tagger changed the title.
        val counter = user.user().changeCounter()
        index.volume().rows = listOf(row(1).copy(size = 9_000, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        readAndReport(StampReader(titles = mapOf(1L to "Retagged")))

        val states = user.decisions().fileStates(identityOf(1))
        assertThat(states.map { DecidedField.ofCode(it.field) to it.changedInFile }).containsExactly(DecidedField.TITLE to true)
        assertThat(states.single().relativePath).isEqualTo(file.relativePath)
        // Flags are not mirrored: no counter moved, and the decision keeps winning.
        assertThat(user.user().changeCounter()).isEqualTo(counter)
        assertThat(display(1).title).isEqualTo("Song")
    }

    @Test
    fun aReadMatchingAPendingTagWriteDoesNotFlagItsDecidedValue() = runBlocking<Unit> {
        val oldTags = mapOf("TITLE" to listOf("Before"))
        val writtenTags = mapOf("TITLE" to listOf("Song"))
        val newSize = 9_000L
        library(listOf(row(1)))
        readAndReport(PropertiesReader(oldTags))
        val file = library.library().files().single()
        val identity = identityOf(1)
        val location = Decisions.LocationValues(file.volumeName, file.relativePath, file.displayName, DecisionContent.ofTags(TrackTags.from(oldTags)))
        val applied = checkNotNull(decisions.apply(request(key(1), fields = setOf(ProtectedField.TITLE)), atDecision = listOf(location)))
        TagWriteJournal(user).prepare(
            TagBackupEntity(
                identityId = identity, volumeName = file.volumeName, relativePath = file.relativePath, displayName = file.displayName,
                createdAt = 1, tagsPath = "tags.json", tagsHash = ByteArray(32), coverPath = null, coverHash = null, id3Version = 4,
            ),
            AppliedChangeEntity(
                identityId = identity, decisionId = applied.decisionId, batchUuid = null, backupId = 0,
                volumeName = file.volumeName, relativePath = file.relativePath, displayName = file.displayName,
                state = "pending", copyPath = "copy", copySize = newSize, copySha256 = ByteArray(32),
                expectedContentKey = Hex.bytes(key(1)), tagHash = tagPropertiesHash(writtenTags), diff = byteArrayOf(), createdAt = 1,
            ),
        )

        index.volume().rows = listOf(row(1).copy(size = newSize, modifiedSeconds = 1_800_000_000, generationModified = 70))
        sync.sync()
        readAndReport(PropertiesReader(writtenTags))
        assertThat(user.decisions().fileStates(identity)).isEmpty()
        assertThat(display(1).title).isEqualTo("Song")

        sync.exclusive { library.library().forgetReaderVersions() }
        readAndReport(PropertiesReader(mapOf("TITLE" to listOf("Edited elsewhere"))))
        assertThat(user.decisions().fileStates(identity).filter { it.changedInFile }.map { it.field })
            .containsExactly(DecidedField.TITLE.code)
    }

    /** A reader of the tag pass that gives each file the key of its media ID and the properties of [tags]. */
    private inner class PropertiesReader(val tags: Map<String, List<String>>) : PassReader {
        override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
            TagReport(
                properties = tags,
                audio = TagReport.Audio(200_000, 128, 44_100, 2),
                content = TagReport.Content(key(file.mediaStoreId), "mp3", DurationPlan(200_000, DurationCheck.NONE)),
            ),
        )
    }

    @Test
    fun aReadComparesEveryPartOfADecidedFieldAsThePassStoredIt() = runBlocking<Unit> {
        val tags = DecisionFixtures.wideTags()
        library(listOf(row(1)))
        readAndReport(PropertiesReader(tags))
        val file = library.library().files().single()
        val location = Decisions.LocationValues(file.volumeName, file.relativePath, file.displayName, DecisionContent.ofTags(TrackTags.from(tags)))
        decisions.apply(request(key(1)), atDecision = listOf(location))

        // Read again unchanged: no part is flagged, not even those the lists do not show.
        sync.exclusive { library.library().forgetReaderVersions() }
        readAndReport(PropertiesReader(tags))
        assertThat(user.decisions().fileStates(identityOf(1)).filter { it.changedInFile }.map { DecidedField.ofCode(it.field) }).isEmpty()

        // A tagger changed the number of tracks, the label and the album artists' IDs.
        changeFileOne()
        readAndReport(
            PropertiesReader(
                tags + mapOf(
                    "TRACKTOTAL" to listOf("11"), "LABEL" to listOf("Other label"),
                    "MUSICBRAINZ_ALBUMARTISTID" to listOf(DecisionFixtures.mbid(9, 0xa47)),
                ),
            ),
        )

        assertThat(user.decisions().fileStates(identityOf(1)).filter { it.changedInFile }.map { DecidedField.ofCode(it.field) })
            .containsExactlyInAnyOrder(DecidedField.TRACK_TOTAL, DecidedField.LABEL, DecidedField.ALBUM_ARTIST_IDS)
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
