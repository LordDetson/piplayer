package io.github.lorddetson.piplayer.library.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The attach pass on a fake media index and the real databases (docs/library.md §8.2): the keys the tag pass would
 * store are set directly, so a test can move, rename and change files as it likes.
 */
@RunWith(AndroidJUnit4::class)
class IdentityAttacherTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private var now = 1_800_000_000_000L
    private val attacher = IdentityAttacher(library, user, sync, clock = { now })

    @After
    fun close() {
        attacher.close()
        sync.close()
        library.close()
        user.close()
    }

    private fun key(n: Int) = "%064x".format(n)

    /** Stores what the tag pass would store for the file of index row [mediaStoreId]. */
    private suspend fun keyed(mediaStoreId: Long, key: String, durationMs: Long = 200_000, source: String = DurationSource.HEADER) {
        val file = library.library().files().single { it.mediaStoreId == mediaStoreId }
        library.library().setContent(file.id, key, durationMs, source)
    }

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun identities() = user.identities().identities()

    private fun place(row: IndexRow) = Triple(row.volume, row.relativePath, row.displayName)

    @Test
    fun everyKeyedFileWithASettledLengthGetsAnIdentityWithItsPlace() = runBlocking<Unit> {
        val a = row(1, path = "Music/A/", name = "a.mp3")
        index.volume().rows = listOf(a, row(2), row(3))
        sync.sync()
        keyed(1, key(1))
        keyed(2, key(2), source = DurationSource.PENDING_WALK)

        attacher.attach()

        val stored = identities().single()
        assertThat(stored.contentKey).isEqualTo(Hex.bytes(key(1)))
        assertThat(Triple(stored.hintVolumeName, stored.hintRelativePath, stored.hintDisplayName)).isEqualTo(place(a))
        assertThat(stored.hintSize).isEqualTo(a.size)
        assertThat(stored.durationMs).isEqualTo(200_000)
        assertThat(stored.createdAt).isEqualTo(now)
        assertThat(stored.missingSince).isNull()
    }

    @Test
    fun aPassThatChangesNothingWritesNothingAndRaisesNoCounter() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()
        val before = identities()

        val plan = attacher.attach()

        assertThat(plan.isEmpty).isTrue()
        assertThat(identities().map { it.id }).isEqualTo(before.map { it.id })
        assertThat(user.user().changeCounter()).isZero()
    }

    @Test
    fun aFileMovedInTheIndexKeepsItsIdentityAndTheHintFollows() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/A/"))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()
        val id = identities().single().id

        // The same index row in another folder: an incremental sync keeps the file and what the pass found.
        index.volume().rows = listOf(row(1, path = "Music/B/", generation = 10))
        sync.sync()
        attacher.attach()

        with(identities().single()) {
            assertThat(this.id).isEqualTo(id)
            assertThat(hintRelativePath).isEqualTo("Music/B/")
        }
    }

    @Test
    fun aFileCopiedAndDeletedUnderANewIndexIdKeepsItsIdentityByKey() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, path = "Music/A/", name = "song.mp3"))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()
        val id = identities().single().id

        index.volume().rows = listOf(row(2, path = "Music/Renamed/", name = "Song (2).mp3", generation = 10))
        sync.sync()
        attacher.attach()
        // The new file is not read yet: the identity waits as missing, and is not deleted.
        assertThat(identities().single().missingSince).isEqualTo(now)

        now += 60_000
        keyed(2, key(1))
        attacher.attach()

        with(identities().single()) {
            assertThat(this.id).isEqualTo(id)
            assertThat(hintDisplayName).isEqualTo("Song (2).mp3")
            assertThat(missingSince).isNull()
        }
    }

    @Test
    fun anIndexRebuildWithNewIdsKeepsTheFileAndItsIdentity() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1, name = "a.mp3"), row(2, name = "b.mp3"))
        sync.sync()
        keyed(1, key(1))
        keyed(2, key(2))
        attacher.attach()
        val before = identities().associate { it.hintDisplayName to it.id }
        val fileA = fileId(1)

        index.volume().version = "2"
        index.volume().rows = listOf(row(101, name = "a.mp3"), row(102, name = "b.mp3"))
        sync.sync()
        attacher.attach()

        assertThat(fileId(101)).isEqualTo(fileA)
        assertThat(identities().associate { it.hintDisplayName to it.id }).isEqualTo(before)
    }

    @Test
    fun aFileWhoseAudioChangedInPlaceKeepsItsIdentity() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()
        val id = identities().single().id

        // A gain tool rewrote the frames: another size, so the sync forgets the key until the pass reads it again.
        index.volume().rows = listOf(row(1, generation = 10).copy(size = 9_999))
        sync.sync()
        keyed(1, key(2), durationMs = 200_300)
        attacher.attach()

        with(identities().single()) {
            assertThat(this.id).isEqualTo(id)
            assertThat(contentKey).isEqualTo(Hex.bytes(key(2)))
            assertThat(hintSize).isEqualTo(9_999)
        }
    }

    @Test
    fun aDeletedFilesIdentityGoesMissingAndIsDeletedThirtyDaysLater() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()

        index.volume().rows = emptyList()
        sync.sync()
        attacher.attach()
        assertThat(identities().single().missingSince).isEqualTo(now)

        now += IdentityMatcher.KEEP_MISSING_MS - 1
        attacher.attach()
        assertThat(identities()).hasSize(1)

        now += 1
        attacher.attach()
        assertThat(identities()).isEmpty()
    }

    @Test
    fun anIdentityThatHoldsUserDataStays() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1))
        sync.sync()
        keyed(1, key(1))
        attacher.attach()
        val id = identities().single().id
        val holding = IdentityAttacher(library, user, sync, holders = { setOf(id) }, clock = { now })

        try {
            index.volume().rows = emptyList()
            sync.sync()
            holding.attach()
            now += 10 * IdentityMatcher.KEEP_MISSING_MS
            holding.attach()

            assertThat(identities().map { it.id }).containsExactly(id)
        } finally {
            holding.close()
        }
    }

    @Test
    fun theRequestedPassRunsInTheBackground() = runBlocking<Unit> {
        index.volume().rows = listOf(row(1), row(2, volume = PRIMARY))
        sync.sync()
        keyed(1, key(1))
        keyed(2, key(2))

        attacher.request()
        attacher.awaitIdle()

        assertThat(identities()).hasSize(2)
    }
}
