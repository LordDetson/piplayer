package io.github.lorddetson.piplayer.ui.identification

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.identity.LibraryApplyFiles
import io.github.lorddetson.piplayer.library.mirror.DecisionMirror
import io.github.lorddetson.piplayer.library.mirror.LibraryDecisions
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.fakes.Fixtures
import io.github.lorddetson.piplayer.library.details.PictureSize
import io.github.lorddetson.piplayer.library.values.Decided
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.manualLibraryEdit
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review's decisions in the app (TI §6, review screen A; §11.2 M3 "Review backend in the app"): a fake media index,
 * the real databases, sync, attach pass, tag pass over a fake reader, decision mirror and saved covers. Files 1 and 3
 * are copies (one key) that the tag pass has read with titles of their own; file 2 came in after it and is unread.
 */
@RunWith(AndroidJUnit4::class)
class ReviewDecisionsTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val attacher = IdentityAttacher(library, user, sync, holders = { user.user().identitiesHoldingData().toSet() })
    private val mirror = DecisionMirror(library, user, sync)
    private val base = File(context.cacheDir, "review-decisions-test").apply { deleteRecursively(); mkdirs() }
    private val covers = SavedCovers(user, SavedCovers.directory(base))
    private val review = ReviewDecisions(
        LibraryDecisions(Decisions(user), LibraryApplyFiles(LibraryAccess.of(library))) { mirror },
        LibraryAccess.of(library),
        user,
        covers,
    )

    @Before
    fun library() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(3))
        sync.sync()
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = mapOf("TITLE" to listOf("Tag ${file.mediaStoreId}")),
                        audio = TagReport.Audio(60_000, 128, 44_100, 2),
                        content = TagReport.Content(key(1), "mp3", DurationPlan(60_000, DurationCheck.NONE)),
                    ),
                )
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onBatch = { rows -> mirror.requestFiles(rows.map { it.fileId }) },
        ).run()
        mirror.awaitIdle()
        attacher.attach()
        // A new file the tag pass has not read: stage 0 reads its key from the file itself.
        index.volume().rows = listOf(row(1), row(2, generation = 10), row(3))
        sync.sync()
    }

    @After
    fun close() {
        mirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
        base.deleteRecursively()
    }

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun target(mediaStoreId: Long, key: Long) = IdentificationTarget.LibraryFile(fileId(mediaStoreId), key(key))

    private suspend fun identityOf(n: Long) = user.identities().idOfKey(Hex.bytes(key(n)))

    @Test
    fun keepAsIsAndSkipNeedTheIdentityOfTheKeyAndAreStoredAndUndoneByIt() = runBlocking<Unit> {
        val identity = checkNotNull(identityOf(1))
        val unread = target(2, key = 2)

        assertThat(review.canKeepOrSkip(unread)).isFalse()
        assertThat(review.keepAsIs(unread)).isNull()
        assertThat(review.skip(unread)).isNull()
        assertThat(identityOf(2)).isNull()

        val read = target(1, key = 1)
        assertThat(review.canKeepOrSkip(read)).isTrue()
        val kept = checkNotNull(review.keepAsIs(read))
        val skipped = checkNotNull(review.skip(read))

        val keep = checkNotNull(user.decisions().decision(kept))
        assertThat(keep.identityId).isEqualTo(identity)
        assertThat(keep.kind).isEqualTo(DecisionKind.KEEP_AS_IS.code)
        val skip = checkNotNull(user.decisions().decision(skipped))
        val file = checkNotNull(library.library().file(read.fileId))
        assertThat(skip.identityId).isEqualTo(identity)
        assertThat(skip.kind).isEqualTo(DecisionKind.SKIP.code)
        assertThat(skip.fileSize).isEqualTo(file.size)
        assertThat(skip.fileMtime).isEqualTo(file.mtime * 1_000)

        assertThat(review.undo(skipped)).isTrue()
        assertThat(user.decisions().decision(skipped)?.undoneAt).isNotNull()
        assertThat(review.undo(skipped)).isFalse()
    }

    @Test
    fun applySavesTheCoverAndStoresItWithTheReferencesOfEachReadFileOfTheKeyTheIdentifiedOneFirst() = runBlocking<Unit> {
        val identity = checkNotNull(identityOf(1))
        val image = ReviewFixtures.cover()
        val applied = request(key(1), details(title = "Decided"), fileId = fileId(1))
            .copy(fields = ProtectedField.entries.toSet(), cover = image.source)

        val decisionId = checkNotNull(review.apply(applied, image))

        val decision = checkNotNull(user.decisions().decision(decisionId))
        assertThat(decision.identityId).isEqualTo(identity)
        val choice = user.decisions().releaseChoices(listOf(checkNotNull(decision.releaseChoiceId))).single()
        val saved = checkNotNull(user.decisions().savedCover(checkNotNull(choice.savedCoverId)))
        assertThat(File(covers.dir, saved.path).readBytes()).isEqualTo(image.bytes)
        // The references of the identified file are the decision's own; the copy, whose title differs, gets its state.
        assertThat(decision.fileValues).isNotNull()
        val states = user.decisions().fileStates(identity)
        assertThat(states).isNotEmpty()
        assertThat(states.map { it.displayName }.toSet()).containsExactly("3.mp3")
    }

    /**
     * The tag pass reads every file again, file 1 with [tags] and file 3 with its title as before, and compares them as
     * after each of its batches (TI §7.1).
     */
    private suspend fun readAgain(tags: Map<String, List<String>>) {
        sync.exclusive { library.library().forgetReaderVersions() }
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = if (file.mediaStoreId == 1L) tags else mapOf("TITLE" to listOf("Tag ${file.mediaStoreId}")),
                        audio = TagReport.Audio(60_000, 128, 44_100, 2),
                        content = TagReport.Content(key(if (file.mediaStoreId == 2L) 2 else 1), "mp3", DurationPlan(60_000, DurationCheck.NONE)),
                    ),
                )
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onStored = { read, failed -> mirror.requestRead(read, failed) },
        ).run()
        mirror.awaitIdle()
    }

    /** The parts flagged as changed in the file at file 1's place. */
    private suspend fun flaggedAtFileOne(identity: Long) = user.decisions().fileStates(identity)
        .filter { it.changedInFile && it.displayName == "1.mp3" }.mapNotNull { DecidedField.ofCode(it.field) }

    @Test
    fun applyTakesTheReferencesFromEveryPartTheTagPassStored() = runBlocking<Unit> {
        val tags = DecisionFixtures.wideTags()
        readAgain(tags)
        val identity = checkNotNull(identityOf(1))
        checkNotNull(review.apply(request(key(1), details(title = "Decided"), fileId = fileId(1)), cover = null))

        // Read again unchanged: nothing is flagged.
        readAgain(tags)
        assertThat(flaggedAtFileOne(identity)).isEmpty()

        // A tagger changed the number of tracks, which the lists do not show: the track is flagged.
        readAgain(tags + ("TRACKTOTAL" to listOf("11")))
        assertThat(flaggedAtFileOne(identity)).containsExactly(DecidedField.TRACK_TOTAL)
    }

    /** The edit of review screen B: [values] and an embedded picture chosen as the cover. */
    private fun edit(target: IdentificationTarget, values: Map<DecidedField, String?>, cover: Boolean = true) = ReviewEdit(
        target,
        values,
        if (cover) ProposedCover.Chosen(Fixtures.bytes("caa/front-250.jpg"), PictureSize(250, 250), ProposedCover.Origin.EMBEDDED) else null,
        releaseId = details().releaseId,
    )

    @Test
    fun aManualLibraryEditWinsInPiPlayerAndUndoRestoresTheFileValue() = runBlocking<Unit> {
        val read = target(1, key = 1)
        val identity = checkNotNull(identityOf(1))
        val rawTitle = listOf("Tag 1")
        val values = checkNotNull(manualLibraryEdit(
            mapOf("TITLE" to rawTitle), mapOf("TITLE" to TagChange(rawTitle, listOf("My title"))),
        ))
        val saved = checkNotNull(review.applyWithEdit(null, null, edit(read, values, cover = false)))
        assertThat(Decisions(user).decidedOf(listOf(identity)).getValue(identity).getValue(DecidedField.TITLE).value)
            .isEqualTo("My title")
        assertThat(library.library().tracksOf(listOf(read.fileId)).single().title).isEqualTo(rawTitle.single())
        assertThat(review.undo(saved.decisionId)).isTrue()
        assertThat(Decisions(user).decidedOf(listOf(identity))[identity]).isNullOrEmpty()
    }

    @Test
    fun applyWithTheUsersValuesStoresTheIdentificationThenAnEditWithTheSavedCoverWhichWinsAndUndoUndoesBoth() = runBlocking<Unit> {
        val identity = checkNotNull(identityOf(1))
        val read = target(1, key = 1)
        val request = request(key(1), details(title = "Decided", album = "Released"), fileId = read.fileId)
            .copy(fields = setOf(ProtectedField.ALBUM))
        val edit = edit(read, mapOf(DecidedField.TITLE to "Mine"))

        val applied = checkNotNull(review.applyWithEdit(request, null, edit))

        assertThat(applied.editFailed).isFalse()
        val identification = checkNotNull(user.decisions().decision(applied.decisionId))
        val edited = checkNotNull(user.decisions().decision(checkNotNull(applied.editId)))
        assertThat(identification.kind).isEqualTo(DecisionKind.APPLY.code)
        assertThat(edited.kind).isEqualTo(DecisionKind.EDIT.code)
        assertThat(edited.identityId).isEqualTo(identity)
        assertThat(edited.id).isGreaterThan(identification.id)
        val winners = Decisions(user).decidedOf(listOf(identity)).getValue(identity)
        assertThat(winners.getValue(DecidedField.TITLE)).isEqualTo(Decided("Mine", DecisionKind.LEVEL_USER_EDIT, edited.id))
        assertThat(winners.getValue(DecidedField.ALBUM).value).isEqualTo("Released")
        val cover = winners.getValue(DecidedField.COVER)
        assertThat(cover.level).isEqualTo(DecisionKind.LEVEL_USER_EDIT)
        val saved = checkNotNull(user.decisions().savedCover(checkNotNull(cover.value).toLong()))
        assertThat(saved.source).isEqualTo(SavedCovers.Source.EMBEDDED.stored)
        assertThat(File(covers.dir, saved.path).readBytes()).isEqualTo(edit.cover!!.bytes)

        assertThat(review.undo(checkNotNull(applied.editId))).isTrue()
        assertThat(review.undo(applied.decisionId)).isTrue()
        assertThat(Decisions(user).decidedOf(listOf(identity))[identity].orEmpty()).isEmpty()
    }

    @Test
    fun withOnlyTheUsersValuesTheEditIsStoredAloneByTheIdentityOfTheKey() = runBlocking<Unit> {
        val identity = checkNotNull(identityOf(1))
        val read = target(1, key = 1)
        val unread = target(2, key = 2)
        assertThat(review.canEdit(read)).isTrue()
        assertThat(review.canEdit(unread)).isFalse()

        assertThat(review.applyWithEdit(null, null, edit(unread, mapOf(DecidedField.TITLE to "Mine")))).isNull()
        val applied = checkNotNull(review.applyWithEdit(null, null, edit(read, mapOf(DecidedField.TITLE to "Mine"), cover = false)))

        assertThat(applied.editId).isNull()
        val edited = checkNotNull(user.decisions().decision(applied.decisionId))
        assertThat(edited.kind).isEqualTo(DecisionKind.EDIT.code)
        assertThat(edited.identityId).isEqualTo(identity)
        assertThat(identityOf(2)).isNull()
    }

    @Test
    fun applyOnAFileTheLibraryHasNotReadMakesItsIdentity() = runBlocking<Unit> {
        val decisionId = checkNotNull(review.apply(request(key(2), details(title = "Decided"), fileId = fileId(2)), cover = null))

        val identity = checkNotNull(identityOf(2))
        assertThat(user.decisions().decision(decisionId)?.identityId).isEqualTo(identity)
        assertThat(user.identities().identity(identity)?.hintDisplayName).isEqualTo("2.mp3")
    }
}
