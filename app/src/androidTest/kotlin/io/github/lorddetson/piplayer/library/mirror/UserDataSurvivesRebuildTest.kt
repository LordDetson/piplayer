package io.github.lorddetson.piplayer.library.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Favorites, favorite albums and play counts survive a rebuild of the media index and a deletion of the library
 * database, coming back as the keys come back (docs/library.md §15.4, features.md M2b).
 */
@RunWith(AndroidJUnit4::class)
class UserDataSurvivesRebuildTest {

    private val context = TestFiles.targetContext
    private val rows = listOf(
        row(1, path = "Music/A/", album = "A", albumArtist = "X"),
        row(2, path = "Music/A/", album = "A", albumArtist = "X"),
        row(3, path = "Music/B/", album = "B", albumArtist = "X"),
    )
    private val index = FakeMediaIndex().apply { volume().rows = rows }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun ready() {
        withTimeout(30_000) { Library.status(context).first { it.ready && it.known } }
        Library.sync(context).awaitIdle()
    }

    /** What the tag pass would store: each file's key by its media ID, then the attach pass and the mirror run. */
    private suspend fun keysComeBack() {
        val dao = Library.database(context).library()
        for (file in dao.files()) dao.setContent(file.id, key(file.mediaStoreId % 100), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        // The attach pass asks the mirror for a run.
        Library.mirror(context).awaitIdle()
    }

    private fun lists() = Library.read(context) { LibraryLists(it) }

    private fun favoriteSongs() = Library.read(context) { database ->
        runBlocking { database.mirror().tracks().filter { it.favorite }.map { it.fileId } }
    }

    /** The file of row [n] by its name, which an index rebuild keeps while it gives new IDs. */
    private suspend fun fileOf(n: Long) = Library.database(context).library().files().single { it.displayName == "$n.mp3" }.id

    /** Song 1 a favorite, album A a favorite, song 3 played twice. */
    private suspend fun userData(): String {
        ready()
        keysComeBack()
        val user = UserData.database(context)
        val favorites = UserData.favorites(context)
        favorites.set(checkNotNull(user.identities().idOfKey(Hex.bytes(key(1)))), favorite = true)
        val albumA = checkNotNull(lists().links(fileOf(1))?.albumKey)
        favorites.setAlbum(albumA, favorite = true)
        val three = checkNotNull(user.identities().idOfKey(Hex.bytes(key(3))))
        UserData.plays(context).record(three, startedAt = 10, listenedMs = 100_000)
        UserData.plays(context).record(three, startedAt = 20, listenedMs = 100_000)
        // The raised change counter asks the mirror for a run as well; this one is the test's own.
        Library.mirror(context).mirror()
        assertMirrored(albumA)
        return albumA
    }

    private suspend fun assertMirrored(albumA: String) {
        assertThat(favoriteSongs()).containsExactly(fileOf(1))
        assertThat(lists().albums(favoritesOnly = true).map { it.key }).containsExactly(albumA)
        assertThat(lists().mostPlayed(limit = 10).map { it.id }).containsExactly(fileOf(3))
        assertThat(Library.read(context) { runBlocking { it.mirror().tracks() } }.single { it.fileId == fileOf(3) }.playCount)
            .isEqualTo(2)
    }

    @Test
    fun userDataSurvivesAMediaIndexRebuildWithNewIds() = runBlocking<Unit> {
        val albumA = userData()

        index.volume().version = "2"
        index.volume().rows = rows.map { it.copy(mediaStoreId = it.mediaStoreId + 100, generationModified = 1) }
        Library.sync(context).sync()
        Library.mirror(context).awaitIdle()

        assertMirrored(albumA)
    }

    @Test
    fun userDataSurvivesARebuildOfTheLibrary() = runBlocking<Unit> {
        val albumA = userData()

        Library.rebuild(context)
        ready()
        Library.mirror(context).awaitIdle()
        // No keys yet: nothing to show.
        assertThat(favoriteSongs()).isEmpty()

        keysComeBack()
        assertMirrored(albumA)
    }

    @Test
    fun userDataSurvivesADeletionOfTheLibraryDatabase() = runBlocking<Unit> {
        val albumA = userData()

        Library.reopen(context, index, empty = true)
        LibraryRule.startAsANewProcess()
        ready()
        keysComeBack()

        assertMirrored(albumA)
    }
}
