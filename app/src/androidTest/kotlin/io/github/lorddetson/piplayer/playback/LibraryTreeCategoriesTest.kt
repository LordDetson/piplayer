package io.github.lorddetson.piplayer.playback

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.GenreKeys
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The node More of the library tree with Playlists, Genres and Folders, and the genres, playlists and smart lists under it
 * (docs/playback.md §10, docs/library.md §19). The callback is called directly with controller descriptions, as in
 * [LibraryTreePlayTest]; the library is the app's, filled from a test index.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryTreeCategoriesTest {

    private val context = TestFiles.targetContext

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", artist = "Bob", genre = "Rock; Pop"),
            row(2, "Bravo", artist = "Ann", genre = "Rock"),
            row(3, "Charlie", artist = "Cid", genre = "Jazz"),
            row(4, "Delta", artist = "Dee", genre = null),
            row(5, "Echo", artist = "Eve", genre = "Unknown genre"),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var callback: PlaybackSessionCallback
    private lateinit var store: PlaybackStateStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var session: MediaLibrarySession
    private val stateFile = File(context.cacheDir, "categories-state-${UUID.randomUUID()}.preferences_pb")
    private val queueRequests = CopyOnWriteArrayList<QueueState?>()

    private val piPlayer = controller(context.packageName, Process.myUid(), trusted = true)
    private val foreignUid = (Process.myUid() + 1..Process.LAST_APPLICATION_UID).first {
        context.checkPermission(AudioPermission.name, -1, it) != PackageManager.PERMISSION_GRANTED
    }
    private val otherApp = controller("com.example.remote", foreignUid, trusted = false)

    @Before
    fun createSession() {
        runBlocking { Library.sync(context).awaitIdle() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PlaybackStateStore(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { stateFile }))
        callback = PlaybackSessionCallback(context, store) { queueRequests += it }
        session = onMain {
            MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).setId("categories-test").build()
        }
    }

    @After
    fun releaseSession() {
        onMain {
            val player = session.player
            session.release()
            player.release()
            callback.release()
        }
        store.close()
        storeScope.cancel()
        stateFile.delete()
    }

    private fun children(id: String, controller: MediaSession.ControllerInfo = piPlayer): LibraryResult<*> =
        onMain { callback.onGetChildren(session, controller, id, 0, Int.MAX_VALUE, null) }.get(30, TimeUnit.SECONDS)

    @Suppress("UNCHECKED_CAST")
    private fun items(id: String): List<MediaItem> {
        val result = children(id)
        assertThat(result.resultCode).describedAs("children of $id").isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value as List<MediaItem>
    }

    private fun item(id: String): LibraryResult<MediaItem> = onMain { callback.onGetItem(session, piPlayer, id) }.get(30, TimeUnit.SECONDS)

    private fun titles(id: String) = items(id).map { it.mediaMetadata.title.toString() }

    private fun play(item: MediaItem, controller: MediaSession.ControllerInfo = piPlayer): MediaItemsWithStartPosition =
        onMain { callback.onSetMediaItems(session, controller, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }.get(30, TimeUnit.SECONDS)

    private fun play(id: String, controller: MediaSession.ControllerInfo = piPlayer) = play(MediaItem.Builder().setMediaId(id).build(), controller)

    private fun add(id: String) = onMain { callback.onAddMediaItems(session, piPlayer, listOf(MediaItem.Builder().setMediaId(id).build())) }
        .get(30, TimeUnit.SECONDS)

    private fun voice(query: String): MediaItem =
        MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()

    private fun MediaItemsWithStartPosition.titles() = mediaItems.map { it.mediaMetadata.title.toString() }

    private suspend fun fileId(mediaStoreId: Long) = Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id

    private fun key(n: Long) = "%064x".format(n)

    /** Gives every song an identity, so that it can be a favorite and be played (docs/library.md §8.1, §15). */
    private suspend fun identities() {
        val dao = Library.database(context).library()
        for (n in 1L..5L) dao.setContent(fileId(n), key(n), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
    }

    private suspend fun identity(n: Long) = checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(n))))

    private suspend fun playlist(name: String, vararg songs: Long): Long {
        val id = UserData.playlists(context).create(name)
        Library.playlists(context).addFiles(id, songs.map { fileId(it) })
        return id
    }

    @Test
    fun theRootHasMoreWithPlaylistsGenresAndFolders() {
        assertThat(items(PlaybackSessionCallback.ROOT_ID).map { it.mediaId }).containsExactly("songs", "albums", "artists", "more")

        val more = items("more")

        assertThat(more.map { it.mediaId }).containsExactly("playlists", "genres", "folders")
        assertThat(more.map { it.mediaMetadata.title.toString() }).containsExactly("Playlists", "Genres", "Folders")
        assertThat(more.map { it.mediaMetadata.mediaType }).containsExactly(
            MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, MediaMetadata.MEDIA_TYPE_FOLDER_GENRES, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        )
        assertThat(more).allSatisfy {
            assertThat(it.mediaMetadata.isBrowsable).isTrue()
            assertThat(it.mediaMetadata.isPlayable).isFalse()
        }
        val root = item("more").value!!
        assertThat(root.mediaMetadata.title.toString()).isEqualTo("More")
        // Folders kept its ID when it moved under More.
        assertThat(titles("folders")).containsExactly("Music")
    }

    @Test
    fun theGenresFollowTheGenresTabWithTheSongsWithoutOneLast() {
        val genres = items("genres")

        assertThat(genres.map { it.mediaMetadata.title.toString() }).containsExactly("Jazz", "Pop", "Rock", "Unknown genre", "Unknown genre")
        assertThat(genres.last().mediaId).isEqualTo("genre/%21none")
        assertThat(genres[3].mediaId).isEqualTo(TreeNode.Genre(GenreKeys.of("Unknown genre")).mediaId)
        assertThat(genres).allSatisfy {
            assertThat(it.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_GENRE)
            assertThat(it.mediaMetadata.isPlayable).isTrue()
            assertThat(it.mediaMetadata.artworkUri).isNull()
        }
        // A genre's songs in the genre page's order, by artist; each names the genre's list.
        val rock = items(TreeNode.Genre("rock").mediaId)
        assertThat(rock.map { it.mediaMetadata.title.toString() }).containsExactly("Bravo", "Alpha")
        assertThat(QueueRequest.parse(rock.first().mediaId)!!.list).isEqualTo(SongQuery(ListKind.GENRE, "rock", SongSort.ARTIST))
        assertThat(titles("genre/%21none")).containsExactly("Delta")
        assertThat(item("genre/nothing").resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        assertThat(children("genre/nothing").resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
    }

    @Test
    fun aGenreAPlaylistAndASmartListPlayAndAreAdded() = runBlocking<Unit> {
        val mix = playlist("Mix", 3, 1)

        assertThat(play("genre/rock").titles()).containsExactly("Bravo", "Alpha")
        PlaybackTesting.eventually("the genre is reported") { queueRequests.lastOrNull()?.library?.list?.kind == ListKind.GENRE }
        assertThat(play("playlist/$mix").titles()).containsExactly("Charlie", "Alpha")
        assertThat(play("smart/added").titles()).hasSize(5)

        assertThat(add("genre/jazz").map { it.mediaMetadata.title.toString() }).containsExactly("Charlie")
        assertThat(add("playlist/$mix").map { it.mediaMetadata.title.toString() }).containsExactly("Charlie", "Alpha")
        assertThat(add("smart/added")).hasSize(5)
        for (id in listOf("more", "playlists", "genres")) {
            assertThatThrownBy { play(id) }.describedAs(id).isInstanceOf(ExecutionException::class.java)
        }
    }

    @Test
    fun thePlaylistsNodeListsTheSmartListsThatHoldASongThenThePlaylistsByName() = runBlocking<Unit> {
        // Without favorites, plays or playlists only Recently added holds songs.
        assertThat(items("playlists").map { it.mediaId }).containsExactly("smart/added")

        identities()
        UserData.plays(context).record(identity(3), startedAt = 100, listenedMs = 1)
        Library.mirror(context).mirror()
        playlist("zed", 2)
        playlist("Alpha mix", 1)

        val nodes = items("playlists")

        assertThat(nodes.map { it.mediaMetadata.title.toString() })
            .containsExactly("Recently added", "Recently played", "Most played", "Alpha mix", "zed")
        assertThat(nodes).allSatisfy {
            assertThat(it.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            assertThat(it.mediaMetadata.isPlayable).isTrue()
            assertThat(item(it.mediaId).value!!.mediaMetadata.title).isEqualTo(it.mediaMetadata.title)
        }
        assertThat(titles("smart/recent")).containsExactly("Charlie")
        // Songs missing information is not in the tree.
        assertThat(item("smart/missing").resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
    }

    @Test
    fun aFirstFavoriteShowsFavoritesAndTheLastOneRemovedHidesItWithoutANewRevision() = runBlocking<Unit> {
        identities()
        val revision = Library.revision()
        assertThat(items("playlists").map { it.mediaId }).doesNotContain("smart/favorites")

        UserData.favorites(context).set(identity(2), favorite = true)
        Library.mirror(context).mirror()
        assertThat(items("playlists").map { it.mediaId }.first()).isEqualTo("smart/favorites")
        assertThat(titles("smart/favorites")).containsExactly("Bravo")

        UserData.favorites(context).set(identity(2), favorite = false)
        Library.mirror(context).mirror()
        assertThat(items("playlists").map { it.mediaId }).doesNotContain("smart/favorites")
        assertThat(Library.revision()).isEqualTo(revision)
    }

    @Test
    fun aPlaylistListsEachCopyOfASongAndPlaysFromTheTappedOne() = runBlocking<Unit> {
        val twice = playlist("Twice", 1, 2, 1)

        val songs = items("playlist/$twice")

        assertThat(songs.map { it.mediaMetadata.title.toString() }).containsExactly("Alpha", "Bravo", "Alpha")
        assertThat(songs.map { it.mediaId }).doesNotHaveDuplicates()
        val second = play(songs[2])
        assertThat(second.titles()).containsExactly("Alpha", "Bravo", "Alpha")
        assertThat(second.startIndex).isEqualTo(2)
        assertThat(QueueRequest.parse(songs[2].mediaId)!!.list).isEqualTo(SongQuery.playlist(twice))
    }

    @Test
    fun aLongPlaylistSplitsIntoRunsOfRowsAndAnEditIsSeenAtTheNextBrowse() = runBlocking<Unit> {
        val long = playlist("Long", *LongArray(600) { it % 5 + 1L })

        val runs = items("playlist/$long")

        assertThat(runs.size).isGreaterThan(1)
        assertThat(runs.first().mediaMetadata.title.toString()).startsWith("1-")
        assertThat(runs.sumOf { items(it.mediaId).size }).isEqualTo(600)
        assertThat(runs).allSatisfy { assertThat(it.mediaMetadata.isPlayable).isTrue() }

        val short = playlist("Short", 1, 2)
        assertThat(titles("playlist/$short")).containsExactly("Alpha", "Bravo")
        Library.playlists(context).addFiles(short, listOf(fileId(3)))
        assertThat(titles("playlist/$short")).containsExactly("Alpha", "Bravo", "Charlie")
        UserData.playlists(context).rename(short, "Shorter")
        assertThat(items("playlists").map { it.mediaMetadata.title.toString() }).contains("Shorter")

        UserData.playlists(context).delete(short)
        assertThat(children("playlist/$short").resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        assertThat(item("playlist/$short").resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        assertThatThrownBy { play("playlist/$short") }.isInstanceOf(ExecutionException::class.java)
    }

    @Test
    fun anAppWithoutAccessToMusicIsRefusedEveryCategory() = runBlocking<Unit> {
        val mix = playlist("Mix", 1)
        for (id in listOf("more", "playlists", "genres", "genre/rock", "playlist/$mix", "smart/added")) {
            assertThat(children(id, otherApp).resultCode).describedAs(id).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(onMain { callback.onGetItem(session, otherApp, id) }.get(10, TimeUnit.SECONDS).resultCode)
                .describedAs(id).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
        }
        for (id in listOf("genre/rock", "playlist/$mix", "smart/added")) {
            assertThatThrownBy { play(id, otherApp) }.describedAs(id).isInstanceOf(ExecutionException::class.java)
        }
    }

    @Test
    fun aVoiceQueryThatNamesAGenreOrAPlaylistPlaysIt() = runBlocking<Unit> {
        val chill = playlist("Evening chill", 3, 2)

        // "rock" is in the search text of Alpha and Bravo: the genre plays in the genre page's order.
        assertThat(play(voice("rock")).titles()).containsExactly("Bravo", "Alpha")
        assertThat(queueRequests.isNotEmpty()).isTrue()
        assertThat(play(voice("evening chill")).titles()).containsExactly("Charlie", "Bravo")
        assertThat(play(voice("recently added")).titles()).hasSize(5)
        // An artist's name still wins.
        assertThat(play(voice("ann")).titles()).containsExactly("Bravo")
        assertThat(chill).isPositive()
    }

    @Test
    fun everyNodeUnderMoreResolvesWithoutArtwork() = runBlocking<Unit> {
        identities()
        UserData.favorites(context).set(identity(1), favorite = true)
        Library.mirror(context).mirror()
        playlist("Mix", 1, 2)
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf("more"))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            for (child in items(id)) {
                assertThat(child.mediaMetadata.artworkData).describedAs(child.mediaId).isNull()
                val resolved = item(child.mediaId)
                assertThat(resolved.resultCode).describedAs(child.mediaId).isEqualTo(LibraryResult.RESULT_SUCCESS)
                assertThat(resolved.value!!.mediaMetadata.title).describedAs(child.mediaId).isEqualTo(child.mediaMetadata.title)
                if (child.mediaMetadata.isBrowsable == true && seen.add(child.mediaId)) queue.addLast(child.mediaId)
            }
        }
        assertThat(seen).contains("playlists", "genres", "folders", "smart/favorites", "smart/added", "genre/rock", "genre/%21none")
    }

    private fun controller(packageName: String, uid: Int, trusted: Boolean, hints: Bundle = Bundle.EMPTY) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            /* pid= */ 0,
            uid,
            MediaLibraryInfo.VERSION_INT,
            /* interfaceVersion= */ 1,
            trusted,
            hints,
            /* isPackageNameVerified= */ true,
        )
}
