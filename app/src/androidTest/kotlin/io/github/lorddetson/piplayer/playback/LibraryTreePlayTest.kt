package io.github.lorddetson.piplayer.playback

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
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
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.settings.ListName
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.settings.ListSorts
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
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
 * Who may browse the library tree, and what playing from it queues (docs/playback.md §10.3, §10.4). Controllers of other
 * apps cannot be made in a test, so the callback is called directly with controller descriptions, as in
 * [PlaybackSessionCallbackTest]; the library is the app's, filled from a test index.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryTreePlayTest {

    private val context = TestFiles.targetContext

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Bravo", path = "Music/Ann/First/", name = "02 Bravo.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 2),
            row(2, "Alpha", path = "Music/Ann/First/", name = "01 Alpha.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 1),
            row(3, "Charlie", path = "Music/Bob/Second/", name = "01 Charlie.mp3", artist = "Bob", album = "Second", year = 2001, trackNumber = 1),
            row(4, "Delta", path = "Music/Bob/Second/", name = "02 Delta.mp3", artist = "Bob", album = "Second", year = 2001, trackNumber = 2),
            row(5, "Golf", path = "Music/", name = "golf.mp3", artist = "Ann", album = null, year = null),
            row(6, "Second Hand", path = "Music/", name = "hand.mp3", artist = "Cid", album = null, year = null),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var callback: PlaybackSessionCallback
    private lateinit var store: PlaybackStateStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var session: MediaLibrarySession
    private val stateFile = File(context.cacheDir, "tree-state-${UUID.randomUUID()}.preferences_pb")
    private val queueRequests = CopyOnWriteArrayList<QueueState?>()

    private val piPlayer = controller(context.packageName, Process.myUid(), trusted = true)
    private val foreignUid = (Process.myUid() + 1..Process.LAST_APPLICATION_UID).first {
        context.checkPermission(AudioPermission.name, -1, it) != PackageManager.PERMISSION_GRANTED
    }
    private val otherApp = controller("com.example.remote", foreignUid, trusted = false)
    private val shell = controller("com.android.shell", Process.SHELL_UID, trusted = false)
    private val notification = controller(
        context.packageName,
        Process.myUid(),
        trusted = true,
        hints = Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) },
    )

    @Before
    fun createSession() {
        runBlocking { Library.sync(context).awaitIdle() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PlaybackStateStore(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { stateFile }))
        callback = PlaybackSessionCallback(context, store) { queueRequests += it }
        session = onMain {
            MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).setId("tree-test").build()
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
    private fun items(id: String): List<MediaItem> = (children(id).value as List<MediaItem>)

    private fun play(item: MediaItem, controller: MediaSession.ControllerInfo = piPlayer): MediaItemsWithStartPosition =
        onMain { callback.onSetMediaItems(session, controller, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }.get(30, TimeUnit.SECONDS)

    private fun play(id: String) = play(MediaItem.Builder().setMediaId(id).build())

    private fun voice(query: String): MediaItem =
        MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()

    private fun MediaItemsWithStartPosition.titles() = mediaItems.map { it.mediaMetadata.title.toString() }

    private fun MediaItemsWithStartPosition.current() = mediaItems[startIndex].mediaMetadata.title.toString()

    /** The queue's state reaches the saver on the main thread, after the items: waits until it names [list]. */
    private fun reported(list: SongQuery) =
        PlaybackTesting.eventually("the queue of $list is reported") { queueRequests.lastOrNull()?.library?.list == list }

    private fun albumId(title: String) = items("albums").single { it.mediaMetadata.title.toString() == title }.mediaId

    @Test
    fun appsWithoutAccessToMusicGetTheRootAndNothingMore() {
        for (controller in listOf(otherApp, notification)) {
            val root = onMain { callback.onGetLibraryRoot(session, controller, null) }.get(10, TimeUnit.SECONDS)
            assertThat(root.resultCode).describedAs(controller.toString()).isEqualTo(LibraryResult.RESULT_SUCCESS)
            assertThat(children(PlaybackSessionCallback.ROOT_ID, controller).value as List<*>).describedAs(controller.toString()).isEmpty()

            for (id in listOf("songs", "albums", "artists", "folders", "artist/ann")) {
                assertThat(children(id, controller).resultCode).describedAs("$controller $id").isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
                assertThat(onMain { callback.onGetItem(session, controller, id) }.get(10, TimeUnit.SECONDS).resultCode)
                    .describedAs("$controller $id").isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            }
            assertThat(onMain { callback.onSearch(session, controller, "ann", null) }.get(10, TimeUnit.SECONDS).resultCode)
                .isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(onMain { callback.onGetSearchResult(session, controller, "ann", 0, 10, null) }.get(10, TimeUnit.SECONDS).resultCode)
                .isEqualTo(SessionError.ERROR_PERMISSION_DENIED)

            val song = items("artist/ann").last()
            for (item in listOf(song, MediaItem.Builder().setMediaId("artist/ann").build(), voice("ann"), MediaItem.Builder().setMediaId("1").build())) {
                assertThatThrownBy { play(item, controller) }.describedAs("$controller ${item.mediaId}").isInstanceOf(ExecutionException::class.java)
                assertThatThrownBy { onMain { callback.onAddMediaItems(session, controller, listOf(item)) }.get(10, TimeUnit.SECONDS) }
                    .describedAs("$controller ${item.mediaId}").isInstanceOf(ExecutionException::class.java)
            }
        }
        assertThat(queueRequests).isEmpty()
    }

    @Test
    fun anAppThatMayReadMusicItselfMayBrowseAndPlay() {
        // As in PlaybackSessionCallbackTest: the shell holds the audio permission on API 30, not on API 36.
        if (context.checkPermission(AudioPermission.name, -1, Process.SHELL_UID) != PackageManager.PERMISSION_GRANTED) {
            Log.i("LibraryTreePlayTest", "Skipped: the shell may not read music on this device")
            return
        }

        assertThat((children("artist/ann", shell).value as List<*>)).hasSize(4)
        assertThat(play(MediaItem.Builder().setMediaId("artist/ann").build(), shell).titles()).containsExactly("Alpha", "Bravo", "Golf")
    }

    @Test
    fun aSongPlaysTheListItIsShownInFromIt() {
        val first = albumId("First")
        val bravo = items(first).single { it.mediaMetadata.title.toString() == "Bravo" }

        val album = play(bravo)

        assertThat(album.titles()).containsExactly("Alpha", "Bravo")
        assertThat(album.current()).isEqualTo("Bravo")
        assertThat(album.mediaItems).allSatisfy { assertThat(it.localConfiguration).isNotNull() }
        reported(SongQuery(ListKind.ALBUM, first.removePrefix("album/").let { MediaIdText.decode(it)!! }))

        val golfOfArtist = items("artist/ann").last()
        assertThat(play(golfOfArtist).let { it.titles() to it.current() }).isEqualTo(listOf("Alpha", "Bravo", "Golf") to "Golf")

        val music = TreeNode.Folder(FolderKey(FakeMediaIndex.PRIMARY, "Music/")).mediaId
        val golfOfFolder = items(music).single { it.mediaMetadata.title.toString() == "Golf" }
        assertThat(play(golfOfFolder).let { it.titles() to it.current() }).isEqualTo(listOf("Golf", "Second Hand") to "Golf")

        val delta = items("songs").single { it.mediaMetadata.title.toString() == "Delta" }
        val songs = play(delta)
        assertThat(songs.titles()).containsExactly("Alpha", "Bravo", "Charlie", "Delta", "Golf", "Second Hand")
        assertThat(songs.current()).isEqualTo("Delta")
    }

    @Test
    fun aNodePlaysItsListFromTheStartInTheOrderTheAppKeeps() {
        assertThat(play(albumId("Second")).let { it.titles() to it.startIndex }).isEqualTo(listOf("Charlie", "Delta") to 0)
        assertThat(play("artist/ann").titles()).containsExactly("Alpha", "Bravo", "Golf")
        // A folder plays with all its subfolders: its own songs first, then each subfolder by name.
        val music = TreeNode.Folder(FolderKey(FakeMediaIndex.PRIMARY, "Music/")).mediaId
        assertThat(play(music).titles()).containsExactly("Golf", "Second Hand", "Alpha", "Bravo", "Charlie", "Delta")
        PlaybackTesting.eventually("the tree is reported") { queueRequests.lastOrNull()?.library?.list?.kind == ListKind.TREE }

        runBlocking { ListSorts(context).set(ListName.SONGS, ListOrder("title", reversed = true)) }
        val songs = play("songs")
        assertThat(songs.titles()).containsExactly("Second Hand", "Golf", "Delta", "Charlie", "Bravo", "Alpha")
        assertThat(songs.startIndex).isZero()
        reported(SongQuery(ListKind.SONGS, sort = SongSort.TITLE, reversed = true))
    }

    @Test
    fun theTabsAndBucketsOfGroupsDoNotPlay() {
        for (id in listOf("albums", "artists", "folders", "root", "album/nothing")) {
            assertThatThrownBy { play(id) }.describedAs(id).isInstanceOf(ExecutionException::class.java)
        }
    }

    @Test
    fun aVoiceRequestPlaysTheBestMatchWithItsList() {
        assertThat(play(voice("ANN")).titles()).containsExactly("Alpha", "Bravo", "Golf")
        // An album titled "Second" before the song "Second Hand".
        assertThat(play(voice("second")).titles()).containsExactly("Charlie", "Delta")
        val found = play(voice("golf"))
        assertThat(found.titles()).containsExactly("Golf")
        reported(SongQuery(ListKind.SONGS, sort = SongSort.TITLE, search = "golf"))
        assertThat(play(voice("hand")).current()).isEqualTo("Second Hand")
        assertThat(play(voice("")).titles()).hasSize(6)
        assertThatThrownBy { play(voice("zzzq")) }.isInstanceOf(ExecutionException::class.java)
    }

    @Test
    fun addsResolveTreeItemsQueueItemsAndSearchesIntoSongs() {
        val first = MediaItem.Builder().setMediaId(albumId("First")).build()
        val added = onMain {
            callback.onAddMediaItems(session, piPlayer, listOf(first, MediaItem.Builder().setMediaId("5").build(), voice("bob")))
        }.get(30, TimeUnit.SECONDS)

        assertThat(added.map { it.mediaMetadata.title.toString() }).containsExactly("Alpha", "Bravo", "Golf", "Charlie", "Delta")
        assertThat(added).allSatisfy {
            assertThat(QueueIds.addedNumber(it.mediaId)).isNotNull()
            assertThat(it.localConfiguration).isNotNull()
        }
        assertThat(added.map { it.mediaId }.distinct()).hasSize(5)
        assertThatThrownBy {
            onMain { callback.onAddMediaItems(session, piPlayer, listOf(MediaItem.Builder().setMediaId("albums").build())) }.get(10, TimeUnit.SECONDS)
        }.isInstanceOf(ExecutionException::class.java)
    }

    @Test
    fun aSetOfSeveralLibraryItemsStartsAQueueOfAddedEntries() {
        val items = listOf("2", albumId("Second"), "5").map { MediaItem.Builder().setMediaId(it).build() }

        val set = onMain { callback.onSetMediaItems(session, piPlayer, items, 1, 1_000) }.get(30, TimeUnit.SECONDS)

        assertThat(set.titles()).containsExactly("Alpha", "Charlie", "Delta", "Golf")
        assertThat(set.startIndex).isEqualTo(1)
        assertThat(set.startPositionMs).isEqualTo(1_000)
        assertThat(set.mediaItems.map { QueueIds.addedNumber(it.mediaId) }).containsExactly(1L, 2L, 3L, 4L)
        PlaybackTesting.eventually("the queue state is reported") { queueRequests.lastOrNull()?.edits?.added?.size == 4 }
        assertThat(queueRequests.last()!!.library).isNull()
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
