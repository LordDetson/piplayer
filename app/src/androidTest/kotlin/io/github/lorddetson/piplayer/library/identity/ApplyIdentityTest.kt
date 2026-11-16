package io.github.lorddetson.piplayer.library.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackDisplayEntity
import io.github.lorddetson.piplayer.library.mirror.DecisionMirror
import io.github.lorddetson.piplayer.library.mirror.LibraryDecisions
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.TrackIdentityEntity
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The identity Apply makes (TI §10.2 "An identity at Apply", docs/library.md §8.1; §11.2 M3 "Identity at Apply on the
 * real databases"): a fake media index, the real databases, the real sync, attach pass, tag pass over a fake reader and
 * decision mirror.
 */
@RunWith(AndroidJUnit4::class)
class ApplyIdentityTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private var now = 1_800_000_000_000L
    private val attacher = IdentityAttacher(library, user, sync, holders = { user.user().identitiesHoldingData().toSet() }, clock = { now })
    private val mirror = DecisionMirror(library, user, sync)
    private val repository = Decisions(user) { now }
    private val decisions = LibraryDecisions(repository, LibraryApplyFiles(LibraryAccess.of(library))) { mirror }

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

    private suspend fun identityWithKey(n: Long): TrackIdentityEntity? =
        user.identities().identities().singleOrNull { Hex.text(it.contentKey) == key(n) }

    /** A run of the real tag pass whose reader gives each file the key of its media ID; its batches ask the mirror. */
    private suspend fun readByThePass() {
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = mapOf("TITLE" to listOf("Tag ${file.mediaStoreId}")),
                        audio = TagReport.Audio(60_000, 128, 44_100, 2),
                        content = TagReport.Content(key(file.mediaStoreId), "mp3", DurationPlan(60_000, DurationCheck.NONE)),
                    ),
                )
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onBatch = { rows -> mirror.requestFiles(rows.map { it.fileId }) },
        ).run()
        mirror.awaitIdle()
    }

    @Test
    fun anOpenedFilesIdentityIsMissingFromApplyOnSurvivesThirtyDaysAndIsAttachedOnceTheFileIsInTheLibrary() = runBlocking<Unit> {
        index.volume().rows = listOf(row(2))
        sync.sync()
        val opened = request(key(1), details(title = "Decided title")).copy(
            target = IdentificationTarget.OpenedFile("content://downloads/7", key(1), displayName = "track_07.mp3", size = 4_000_000),
        )

        val applied = checkNotNull(decisions.apply(opened))

        val made = checkNotNull(identityWithKey(1))
        assertThat(applied.identityId).isEqualTo(made.id)
        assertThat(made.hintDisplayName).isEqualTo("track_07.mp3")
        assertThat(made.hintSize).isEqualTo(4_000_000)
        assertThat(made.hintVolumeName).isNull()
        assertThat(made.hintRelativePath).isNull()
        assertThat(made.durationMs).isNull()
        assertThat(made.missingSince).isEqualTo(now)

        // 31 days later the attach pass keeps it: its decision is user data.
        now += 31L * 24 * 60 * 60 * 1_000
        attacher.attach()
        assertThat(identityWithKey(1)?.id).isEqualTo(made.id)

        // The file appears in the index, the tag pass stores its key, and the attach pass attaches the identity.
        index.volume().rows = listOf(row(2), row(1, path = "Music/Downloads/", name = "track_07.mp3", generation = 10))
        sync.sync()
        readByThePass()
        attacher.attach()

        val attached = checkNotNull(identityWithKey(1))
        assertThat(attached.id).isEqualTo(made.id)
        assertThat(attached.missingSince).isNull()
        assertThat(attached.hintRelativePath).isEqualTo("Music/Downloads/")
        assertThat(attached.hintDisplayName).isEqualTo("track_07.mp3")
        assertThat(attached.durationMs).isEqualTo(60_000)
        assertThat(display(1).title).isEqualTo("Decided title")
        assertThat(display(1).badgeState).isEqualTo(DecisionResolver.BADGE_IDENTIFIED)
        assertThat(user.identities().identities()).hasSize(2)
    }

    @Test
    fun applyOnALibraryFileTheTagPassHasNotReadMakesItsIdentityAndItsValuesShowAfterTheRead() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3"))
        sync.sync()
        assertThat(library.library().files().single().tagPassAt).isNull()

        // Stage 0 computed the key through `:tagger`; the library holds none for the file.
        val applied = checkNotNull(decisions.apply(request(key(1), details(title = "Decided title"), fileId = fileId(1))))

        val made = checkNotNull(identityWithKey(1))
        assertThat(applied.identityId).isEqualTo(made.id)
        assertThat(made.hintVolumeName).isEqualTo(FakeMediaIndex.PRIMARY)
        assertThat(made.hintRelativePath).isEqualTo("Music/Record/")
        assertThat(made.hintDisplayName).isEqualTo("01.mp3")
        assertThat(made.missingSince).isNull()
        assertThat(display(1).title).isEqualTo("Song 1")

        readByThePass()
        attacher.attach()

        assertThat(display(1).title).isEqualTo("Decided title")
        assertThat(user.identities().identities().map { it.id }).containsExactly(made.id)
    }

    @Test
    fun applyOnAnUnreadFileGivesItsKeyToTheMissingIdentityHintedAtItsPlace() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3", durationMs = 60_000))
        sync.sync()
        // An identity whose file went missing at this place: its audio changed in place, so stage 0 gives another key.
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 60_400, recordingMbid = null,
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )

        val applied = checkNotNull(decisions.apply(request(key(1), fileId = fileId(1))))

        assertThat(applied.identityId).isEqualTo(missing)
        val taken = checkNotNull(user.identities().identity(missing))
        assertThat(Hex.text(taken.contentKey)).isEqualTo(key(1))
        assertThat(taken.missingSince).isNull()
        assertThat(user.identities().identities()).hasSize(1)
    }

    @Test
    fun applyOnAFileWhoseAudioChangedInPlaceGivesItsKeyToTheIdentityThatStillHoldsTheOldOne() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3"))
        sync.sync()
        library.library().setContent(fileId(1), key(9), 200_000, DurationSource.HEADER)
        attacher.attach()
        val own = checkNotNull(identityWithKey(9)).id
        // A newer missing identity that only names the same place, as of a file once deleted there.
        val namesThePlace = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(8)), durationMs = 200_000, recordingMbid = null,
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )
        assertThat(namesThePlace).isGreaterThan(own)

        // A gain tool rewrote the frames: stage 0 reads key 1, while the library and the identity still hold key 9.
        val applied = checkNotNull(decisions.apply(request(key(1), fileId = fileId(1))))

        assertThat(applied.identityId).isEqualTo(own)
        val moved = checkNotNull(user.identities().identity(own))
        assertThat(Hex.text(moved.contentKey)).isEqualTo(key(1))
        assertThat(moved.missingSince).isNull()
        assertThat(Hex.text(checkNotNull(user.identities().identity(namesThePlace)).contentKey)).isEqualTo(key(8))
    }

    @Test
    fun applyOnAFileWhoseOldKeyAnotherFileHoldsMakesANewIdentity() = runBlocking<Unit> {
        // Files 1 and 2 are byte-identical copies; file 1 changed in place.
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3"), row(2, path = "Music/Copy/", name = "01.mp3"))
        sync.sync()
        library.library().setContent(fileId(1), key(9), 200_000, DurationSource.HEADER)
        library.library().setContent(fileId(2), key(9), 200_000, DurationSource.HEADER)
        attacher.attach()
        val copies = checkNotNull(identityWithKey(9)).id

        val applied = checkNotNull(decisions.apply(request(key(1), fileId = fileId(1))))

        assertThat(applied.identityId).isNotEqualTo(copies)
        assertThat(identityWithKey(9)?.id).isEqualTo(copies)
        assertThat(identityWithKey(1)?.id).isEqualTo(applied.identityId)
    }

    @Test
    fun anAttachPassPlannedBeforeApplyMadeAnIdentityKeepsItAndWritesTheRestOfItsPlan() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3"), row(3))
        sync.sync()
        library.library().setContent(fileId(1), key(1), 200_000, DurationSource.HEADER)
        library.library().setContent(fileId(3), key(3), 200_000, DurationSource.HEADER)
        // A missing identity hinted at file 1's place, which the pass's step 2 gives file 1.
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 200_300, recordingMbid = null,
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )
        // Apply runs after the pass has read the identities and before it writes: with another length at hand it makes a
        // new identity for file 1's key instead.
        var applied: Decisions.Committed? = null
        val racing = IdentityAttacher(
            library, user, sync,
            holders = {
                if (applied == null) {
                    val file = KeyedFile(
                        fileId(1), key(1), Place(FakeMediaIndex.PRIMARY, "Music/Record/", "01.mp3"), size = 1, durationMs = 290_000,
                        settled = true, recordingMbid = null, format = null, tagsRead = false,
                    )
                    applied = repository.apply(request(key(1), fileId = fileId(1)), libraryFile = ApplyFile(file))
                }
                user.user().identitiesHoldingData().toSet()
            },
            clock = { now },
        )
        try {
            racing.attach()
        } finally {
            racing.close()
        }

        val made = checkNotNull(identityWithKey(1))
        assertThat(made.id).isEqualTo(checkNotNull(applied).identityId).isNotEqualTo(missing)
        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(9))
        assertThat(user.identities().identity(missing)?.missingSince).isEqualTo(now - 5_000)
        // The rest of the plan was written in the same transaction.
        assertThat(identityWithKey(3)).isNotNull()
    }

    @Test
    fun applyOnALibraryFileThatIsGoneWritesNothing() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        val before = user.user().changeCounter()

        assertThat(decisions.apply(request(key(1), fileId = 999))).isNull()

        assertThat(user.identities().identities()).isEmpty()
        assertThat(user.user().changeCounter()).isEqualTo(before)
        assertThat(user.decisions().liveValueDecisions()).isEmpty()
    }
}
