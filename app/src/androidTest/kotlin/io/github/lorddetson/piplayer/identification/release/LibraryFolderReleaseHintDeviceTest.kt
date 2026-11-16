package io.github.lorddetson.piplayer.identification.release

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.db.LibraryAccess
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.mirror.DecisionMirror
import io.github.lorddetson.piplayer.library.mirror.LibraryDecisions
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DecodedEncoding
import io.github.lorddetson.piplayer.library.tagpass.PassFile
import io.github.lorddetson.piplayer.library.tagpass.PassRead
import io.github.lorddetson.piplayer.library.tagpass.PassReader
import io.github.lorddetson.piplayer.library.tagpass.TagPass
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.tagging.TagReport
import io.github.lorddetson.piplayer.tagging.contentkey.DurationCheck
import io.github.lorddetson.piplayer.tagging.contentkey.DurationPlan
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.mbid
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The folder hint of the app on the library database (TI §4.2 key 1; §11.2 M3 "Folder release hint on the library
 * database"): decided releases through the real decision mirror and tagged ones through the real tag pass.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFolderReleaseHintDeviceTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val attacher = IdentityAttacher(library, user, sync, holders = { user.user().identitiesHoldingData().toSet() })
    private val mirror = DecisionMirror(library, user, sync)
    private val decisions = LibraryDecisions(Decisions(user)) { mirror }
    private val hint = LibraryFolderReleaseHint(LibraryAccess.of(library))

    @After
    fun close() {
        mirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
    }

    private fun key(n: Long) = "%064x".format(n)

    private fun release(n: Int) = mbid(n, 0x1e1)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    /** Files 1 and 3 in `Music/Record/`, 2 in its disc folder, 4 in another folder; the pass reads them, file 3 with a release tag. */
    private suspend fun library() {
        index.volume().rows = listOf(
            row(1, path = "Music/Record/"),
            row(2, path = "Music/Record/CD2/"),
            row(3, path = "Music/Record/"),
            row(4, path = "Music/Other/"),
        )
        sync.sync()
        TagPass(
            library, sync,
            object : PassReader {
                override suspend fun read(file: PassFile): PassRead = PassRead.Replied(
                    TagReport(
                        properties = buildMap {
                            put("TITLE", listOf("Tag ${file.mediaStoreId}"))
                            if (file.mediaStoreId == 3L) put("MUSICBRAINZ_ALBUMID", listOf(release(2).uppercase()))
                        },
                        audio = TagReport.Audio(200_000, 128, 44_100, 2),
                        content = TagReport.Content(key(file.mediaStoreId), "mp3", DurationPlan(200_000, DurationCheck.NONE)),
                    ),
                )
            },
            cyrillicLanguage = { false }, legacyEncoding = { null }, decodedEncoding = DecodedEncoding.InMemory(),
            crashBackoff = 1.milliseconds,
            onBatch = { rows -> mirror.requestFiles(rows.map { it.fileId }) },
        ).run()
        mirror.awaitIdle()
        attacher.attach()
    }

    private suspend fun identify(mediaStoreId: Long, release: Int) =
        checkNotNull(decisions.apply(request(key(mediaStoreId), details(release = release), fileId = fileId(mediaStoreId))))

    @Test
    fun aReleaseDecidedOrTaggedInTheFolderIsConfirmedForItAndItsDiscFoldersAndRanksFirst() = runBlocking<Unit> {
        library()
        identify(1, release = 1)
        identify(4, release = 3)

        val confirmed = hint.confirmedReleaseIds(FolderKey(FakeMediaIndex.PRIMARY, "Music/Record/CD2/").key)

        assertThat(confirmed).containsExactlyInAnyOrder(release(1), release(2))
        assertThat(hint.confirmedReleaseIds(FolderKey(FakeMediaIndex.PRIMARY, "Music/Record/").key)).isEqualTo(confirmed)
        assertThat(hint.confirmedReleaseIds(FolderKey(FakeMediaIndex.PRIMARY, "Music/Other/").key)).containsExactly(release(3))
        val ranked = ReleaseRanking.rank(
            listOf(ReleaseDto(release(7), "Record", date = "1990"), ReleaseDto(release(1), "Record", date = "2005"), ReleaseDto(release(3), "Record")),
            ReleaseContext(albumTag = "Record", confirmedInFolder = confirmed),
        )
        assertThat(ranked.first().release.id).isEqualTo(release(1))
    }

    @Test
    fun anUndoneDecisionAndADecidedEmptyValueCountForNothing() = runBlocking<Unit> {
        library()
        val first = identify(1, release = 1)
        decisions.undo(first.decisionId)
        // An edit that clears the MusicBrainz IDs of file 3 hides its tags' release.
        decisions.edit(
            checkNotNull(user.identities().idOfKey(Hex.bytes(key(3)))),
            mapOf(DecidedField.RELEASE_MBID to null),
        )

        assertThat(hint.confirmedReleaseIds(FolderKey(FakeMediaIndex.PRIMARY, "Music/Record/").key)).isEmpty()
    }
}
