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
import io.github.lorddetson.piplayer.library.rules.FolderRule
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.values.DecisionResolver
import io.github.lorddetson.piplayer.tagging.TagReadGuard
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
    private val rules = FakeRules()
    private val sync = LibrarySync(library, index, mayRead = { true }, rules)
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

    /**
     * A run of the real tag pass whose reader gives each file the key of its media ID and the [tags] of its media ID; its
     * batches ask the mirror.
     */
    private suspend fun readByThePass(tags: (Long) -> Map<String, List<String>> = { emptyMap() }) {
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = mapOf("TITLE" to listOf("Tag ${file.mediaStoreId}")) + tags(file.mediaStoreId),
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

    /** A run of the real tag pass in a new process whose reader cannot open any file, as for an index row whose file is gone. */
    private suspend fun passThatCannotOpen() {
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.OpenFailed
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
        ).run()
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
    fun anAttachPassThatPlannedANewIdentityForAFileApplyMadeOneForKeepsApplysAndWritesTheRestOfItsPlan() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3"), row(3))
        sync.sync()
        library.library().setContent(fileId(1), key(1), 200_000, DurationSource.HEADER)
        library.library().setContent(fileId(3), key(3), 200_000, DurationSource.HEADER)
        // No identity matches file 1, so the pass plans a new one for its key; Apply makes one after the pass has read the
        // identities and before it writes.
        var applied: Decisions.Committed? = null
        val racing = IdentityAttacher(
            library, user, sync,
            holders = {
                if (applied == null) {
                    val file = KeyedFile(
                        fileId(1), key(1), Place(FakeMediaIndex.PRIMARY, "Music/Record/", "01.mp3"), size = 1, durationMs = 200_000,
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

        // Apply's identity keeps the key, and the pass's write of it is left out rather than failing the whole pass.
        assertThat(identityWithKey(1)?.id).isEqualTo(checkNotNull(applied).identityId)
        assertThat(identityWithKey(3)).isNotNull()
        assertThat(user.identities().identities()).hasSize(2)
    }

    @Test
    fun anAttachPassThatPlannedToMoveAnIdentityApplyMovedLeavesItWhereApplyPutIt() = runBlocking<Unit> {
        val recording = "4e000000-1111-4222-8333-000000000005"
        // File 5 lies elsewhere and its tags carry the recording MBID of the missing identity below.
        index.volume().rows = listOf(row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5))
        sync.sync()
        readByThePass { id -> if (id == 5L) mapOf("MUSICBRAINZ_TRACKID" to listOf(recording)) else emptyMap() }
        // File 1 lies at the place the missing identity names; the tag pass has not read it, so the library has no key.
        index.volume().rows = listOf(
            row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5),
            row(1, path = "Music/Record/", name = "01.mp3", durationMs = 60_000, generation = 10),
        )
        sync.sync()
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 60_300, recordingMbid = Hex.mbidBytes(recording),
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )
        // The pass's step 3 plans to give the missing identity file 5's key by the recording MBID. Before it writes, Apply on
        // file 1 gives the identity file 1's key by the hinted place.
        var applied: Decisions.Committed? = null
        val racing = IdentityAttacher(
            library, user, sync,
            holders = {
                if (applied == null) {
                    val file = KeyedFile(
                        fileId(1), key(1), Place(FakeMediaIndex.PRIMARY, "Music/Record/", "01.mp3"), size = 1, durationMs = 60_000,
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

        assertThat(checkNotNull(applied).identityId).isEqualTo(missing)
        val kept = checkNotNull(user.identities().identity(missing))
        assertThat(Hex.text(kept.contentKey)).isEqualTo(key(1))
        assertThat(kept.hintRelativePath).isEqualTo("Music/Record/")
        assertThat(kept.missingSince).isNull()
    }

    @Test
    fun applyMatchesAnUnreadFileByStage0sExactLengthWhenTheIndexsLengthIsOff() = runBlocking<Unit> {
        // A VBR MP3 without a header: the index's length is 3.5 s off, stage 0 walked the frames for the exact one.
        index.volume().rows = listOf(row(1, path = "Music/Record/", name = "01.mp3", durationMs = 203_500))
        sync.sync()
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 200_000, recordingMbid = null,
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )
        val exact = request(key(1), fileId = fileId(1)).let { request ->
            request.copy(target = IdentificationTarget.LibraryFile(fileId(1), key(1), durationMs = 200_200))
        }

        val applied = checkNotNull(decisions.apply(exact))

        assertThat(applied.identityId).isEqualTo(missing)
        val taken = checkNotNull(user.identities().identity(missing))
        assertThat(Hex.text(taken.contentKey)).isEqualTo(key(1))
        assertThat(taken.durationMs).isEqualTo(200_200)
        assertThat(user.identities().identities()).hasSize(1)
    }

    @Test
    fun anAttachPassBeforeTheTagPassReadsTheFileLeavesApplysIdentityWithItsKeyRatherThanMovingItByItsRecordingMbid() =
        runBlocking<Unit> {
            val recording = "4e000000-1111-4222-8333-000000000006"
            // File 5 lies elsewhere and its tags carry the recording MBID of the missing identity below; no pass has given
            // it an identity yet.
            index.volume().rows = listOf(row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5))
            sync.sync()
            readByThePass { id -> if (id == 5L) mapOf("MUSICBRAINZ_TRACKID" to listOf(recording)) else emptyMap() }
            // File 1 lies at the place the missing identity names; the tag pass has not read it, so the library has no key.
            index.volume().rows = listOf(
                row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5),
                row(1, path = "Music/Record/", name = "01.mp3", durationMs = 60_000, generation = 10),
            )
            sync.sync()
            val missing = user.identities().add(
                TrackIdentityEntity(
                    contentKey = Hex.bytes(key(9)), durationMs = 60_300, recordingMbid = Hex.mbidBytes(recording),
                    hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                    hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
                ),
            )
            val applied = checkNotNull(decisions.apply(request(key(1), details(title = "Decided title"), fileId = fileId(1))))
            assertThat(applied.identityId).isEqualTo(missing)

            // A pass before the tag pass reads file 1 sees the identity as missing: it waits for the read and stays with
            // file 1's key, and file 5, which step 3 would give it, gets no identity while it waits.
            now += 60_000
            attacher.attach()

            val waiting = checkNotNull(user.identities().identity(missing))
            assertThat(Hex.text(waiting.contentKey)).isEqualTo(key(1))
            assertThat(waiting.hintRelativePath).isEqualTo("Music/Record/")
            assertThat(identityWithKey(5)).isNull()

            // Once the tag pass has read file 1, the next pass attaches the identity by its key.
            readByThePass()
            attacher.attach()

            val attached = checkNotNull(user.identities().identity(missing))
            assertThat(Hex.text(attached.contentKey)).isEqualTo(key(1))
            assertThat(attached.missingSince).isNull()
            assertThat(display(1).title).isEqualTo("Decided title")
            assertThat(display(5).title).isEqualTo("Tag 5")
            // File 5 gets an identity of its own once the wait has ended.
            assertThat(identityWithKey(5)?.id).isNotNull().isNotEqualTo(missing)
        }

    /**
     * File 5 lies elsewhere and carries [recording] in its tags; file 1 lies at Music/Record/01.mp3, unread, and Apply
     * gives its stage 0 key 1 to a missing identity hinted there, which carries [recording] too. Returns that identity.
     */
    private suspend fun appliedAtAnUnreadFile(recording: String, folder: String = "Music/Record/"): Long {
        index.volume().rows = listOf(row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5))
        sync.sync()
        readByThePass { id -> if (id == 5L) mapOf("MUSICBRAINZ_TRACKID" to listOf(recording)) else emptyMap() }
        index.volume().rows = listOf(
            row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5),
            row(1, path = folder, name = "01.mp3", durationMs = 60_000, generation = 10),
        )
        sync.sync()
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 60_300, recordingMbid = Hex.mbidBytes(recording),
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = folder, hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )
        assertThat(checkNotNull(decisions.apply(request(key(1), details(title = "Decided title"), fileId = fileId(1)))).identityId)
            .isEqualTo(missing)
        return missing
    }

    @Test
    fun anIdentityHintedAtAFileThatCouldNotBeOpenedWaitsForTheReadOfALaterProcess() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-000000000008")
        // The tag pass could not open file 1 (a transient IO error): it tries the file again in a later process.
        library.library().markFailed(fileId(1), "open", readerVersion = null)

        now += 60_000
        attacher.attach()

        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(1))
        // The tag pass has tried file 1, so file 5, which step 3 would give the identity, is held back no longer.
        val own = checkNotNull(identityWithKey(5)).id
        assertThat(own).isNotEqualTo(missing)

        // A later process reads file 1, and the next pass attaches the identity by its key.
        readByThePass()
        attacher.attach()

        val attached = checkNotNull(user.identities().identity(missing))
        assertThat(Hex.text(attached.contentKey)).isEqualTo(key(1))
        assertThat(attached.missingSince).isNull()
        assertThat(display(1).title).isEqualTo("Decided title")
        assertThat(identityWithKey(5)?.id).isEqualTo(own)
    }

    @Test
    fun aFileThatCannotBeOpenedInEveryProcessKeepsTheIdentityWaitingAndHoldsBackNoFile() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-00000000000c")

        // Two processes in a row cannot open file 1; the tag pass keeps trying it (library.md §5.3).
        repeat(2) { process ->
            passThatCannotOpen()
            assertThat(library.library().files().single { it.mediaStoreId == 1L }.tagFailure).isEqualTo("open")
            now += 60_000
            attacher.attach()

            val waiting = checkNotNull(user.identities().identity(missing))
            assertThat(Hex.text(waiting.contentKey)).describedAs("process $process").isEqualTo(key(1))
            assertThat(waiting.missingSince).isNotNull()
            // File 5, which step 3 would give the identity, has an identity of its own.
            assertThat(identityWithKey(5)?.id).describedAs("process $process").isNotNull().isNotEqualTo(missing)
        }
    }

    @Test
    fun anIdentityHintedAtAFileThatCouldNotBeOpenedStillWaitsAfterTheReaderVersionsAreForgotten() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-00000000000d")
        library.library().markFailed(fileId(1), "open", readerVersion = null)
        // A change of the legacy encoding has every file read again (library.md §5.7): file 1 too.
        sync.exclusive { library.library().forgetReaderVersions() }

        attacher.attach()

        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(1))
    }

    @Test
    fun anIdentityHintedAtAFileWhoseReadCrashedBelowTheUnsafeCountWaits() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-000000000009")
        library.library().markFailed(fileId(1), "crash", readerVersion = null)
        library.library().setUnsafeCount(fileId(1), 1)

        attacher.attach()

        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(1))
    }

    @Test
    fun anIdentityHintedAtAFileMarkedUnsafeDoesNotWait() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-00000000000a")
        library.library().markFailed(fileId(1), "unsafe", readerVersion = null)
        library.library().setUnsafeCount(fileId(1), TagReadGuard.UNSAFE_COUNT)

        attacher.attach()

        // The tag pass never reads file 1 again: step 3 gives the identity file 5's key by the recording MBID.
        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(5))
    }

    @Test
    fun anIdentityHintedAtAnUnreadFileInAnExcludedFolderDoesNotWait() = runBlocking<Unit> {
        val missing = appliedAtAnUnreadFile("4e000000-1111-4222-8333-00000000000b", folder = "Music/Hidden/")
        rules.set(FolderRule(FakeMediaIndex.PRIMARY, "Music/Hidden/", include = false))
        sync.sync()

        attacher.attach()

        // The tag pass does not read files in excluded folders: step 3 gives the identity file 5's key.
        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(5))
    }

    @Test
    fun anIdentityHintedAtAFileTheTagPassCouldNotReadDoesNotWait() = runBlocking<Unit> {
        val recording = "4e000000-1111-4222-8333-000000000007"
        index.volume().rows = listOf(row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5))
        sync.sync()
        readByThePass { id -> if (id == 5L) mapOf("MUSICBRAINZ_TRACKID" to listOf(recording)) else emptyMap() }
        index.volume().rows = listOf(
            row(5, path = "Music/Elsewhere/", name = "05.mp3", generation = 5),
            row(1, path = "Music/Record/", name = "01.mp3", durationMs = 60_000, generation = 10),
        )
        sync.sync()
        // The tag pass tried file 1 with the current reader and could not read it.
        library.library().markFailed(fileId(1), "read", readerVersion = TagPass.READER_VERSION)
        val missing = user.identities().add(
            TrackIdentityEntity(
                contentKey = Hex.bytes(key(9)), durationMs = 60_300, recordingMbid = Hex.mbidBytes(recording),
                hintVolumeName = FakeMediaIndex.PRIMARY, hintRelativePath = "Music/Record/", hintDisplayName = "01.mp3",
                hintSize = 1, createdAt = now - 10_000, missingSince = now - 5_000,
            ),
        )

        attacher.attach()

        // Step 3 gives the missing identity file 5's key by the recording MBID, as for any missing identity.
        assertThat(Hex.text(checkNotNull(user.identities().identity(missing)).contentKey)).isEqualTo(key(5))
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
