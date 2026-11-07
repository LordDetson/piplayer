package io.github.lorddetson.piplayer.library.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.PRIMARY
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.userdata.UserDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Track references on the real databases (docs/playback.md §4.8): what the store saves for the current song, and how a
 * restore finds that song after it moved, after an index rebuild and in a library database that started anew.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTrackRefsTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val attacher = IdentityAttacher(library, user, sync)
    private val refs = LibraryTrackRefs(library, user)

    @After
    fun close() {
        attacher.close()
        sync.close()
        library.close()
        user.close()
    }

    private suspend fun fileOf(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }

    private suspend fun start(): TrackRef {
        index.volume().rows = listOf(row(1, path = "Music/A/", name = "a.mp3", durationMs = 200_000), row(2, path = "Music/A/", name = "b.mp3"))
        sync.sync()
        library.library().setContent(fileOf(1).id, "%064x".format(1), 200_000, DurationSource.HEADER)
        attacher.attach()
        return refs.refOf(fileOf(1).id)!!
    }

    @Test
    fun theStoreSavesTheFileWithItsIdentityKeyPlaceAndLength() = runBlocking<Unit> {
        val ref = start()

        assertThat(ref.fileId).isEqualTo(fileOf(1).id)
        assertThat(ref.identityId).isEqualTo(user.identities().identities().single().id)
        assertThat(ref.contentKey).isEqualTo("%064x".format(1))
        assertThat(ref.place).isEqualTo(Place(PRIMARY, "Music/A/", "a.mp3"))
        assertThat(ref.durationMs).isEqualTo(200_000)
        assertThat(refs.refOf(fileOf(2).id)!!.identityId).isNull()
        assertThat(refs.refOf(999)).isNull()
    }

    @Test
    fun aSongMovedUnderANewIndexIdIsFoundByItsKey() = runBlocking<Unit> {
        val ref = start()
        index.volume().rows = listOf(row(3, path = "Music/B/", name = "a.mp3", generation = 10, durationMs = 200_000), row(2, path = "Music/A/", name = "b.mp3"))
        sync.sync()
        library.library().setContent(fileOf(3).id, "%064x".format(1), 200_000, DurationSource.HEADER)

        assertThat(refs.resolve(ref, refs.libraryId())).isEqualTo(fileOf(3).id)
    }

    @Test
    fun anIndexRebuildKeepsTheFileId() = runBlocking<Unit> {
        val ref = start()
        index.volume().version = "2"
        index.volume().rows = listOf(row(11, path = "Music/A/", name = "a.mp3", durationMs = 200_000), row(12, path = "Music/A/", name = "b.mp3"))
        sync.sync()

        assertThat(refs.resolve(ref, refs.libraryId())).isEqualTo(ref.fileId)
    }

    @Test
    fun aNewLibraryDatabaseFindsTheSongByItsPlaceBeforeTheKeysAreBack() = runBlocking<Unit> {
        val ref = start()
        val fresh = LibraryDatabase.open(context, file = null)
        val freshSync = LibrarySync(fresh, index, mayRead = { true }, FakeRules())
        try {
            // Other IDs: the file IDs of the reference mean nothing here.
            index.volume().rows = listOf(row(2, path = "Music/A/", name = "b.mp3"), row(1, path = "Music/A/", name = "a.mp3", durationMs = 200_000))
            freshSync.sync()
            val freshRefs = LibraryTrackRefs(fresh, user)
            val found = freshRefs.resolve(ref, savedLibraryId = refs.libraryId())

            assertThat(found).isEqualTo(fresh.library().files().single { it.displayName == "a.mp3" }.id)
        } finally {
            freshSync.close()
            fresh.close()
        }
    }
}
