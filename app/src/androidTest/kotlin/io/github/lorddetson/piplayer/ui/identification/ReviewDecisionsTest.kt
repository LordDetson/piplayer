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
import io.github.lorddetson.piplayer.library.values.DecisionKind
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
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

    @Test
    fun applyOnAFileTheLibraryHasNotReadMakesItsIdentity() = runBlocking<Unit> {
        val decisionId = checkNotNull(review.apply(request(key(2), details(title = "Decided"), fileId = fileId(2)), cover = null))

        val identity = checkNotNull(identityOf(2))
        assertThat(user.decisions().decision(decisionId)?.identityId).isEqualTo(identity)
        assertThat(user.identities().identity(identity)?.hintDisplayName).isEqualTo("2.mp3")
    }
}
