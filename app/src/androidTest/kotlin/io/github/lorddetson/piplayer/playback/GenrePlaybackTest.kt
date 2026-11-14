package io.github.lorddetson.piplayer.playback

import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.lists.GenreKeys
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A genre as a queue request (docs/library.md §19.4, docs/playback.md §2.3): it plays from the tapped song, and a saved
 * queue of a genre whose key is gone follows its song. The callback is called directly, as in [SmartListPlaybackTest].
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class GenrePlaybackTest {

    private val context = TestFiles.targetContext

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", artist = "Bob", genre = "Rock; Pop"),
            row(2, "Bravo", artist = "Ann", genre = "Rock"),
            row(3, "Charlie", artist = "Cid", genre = "Pop"),
            row(4, "Delta", artist = "Dee", genre = null),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var callback: PlaybackSessionCallback
    private lateinit var store: PlaybackStateStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var session: MediaLibrarySession
    private val stateFile = File(context.cacheDir, "genre-state-${UUID.randomUUID()}.preferences_pb")
    private val queueFile = File(context.cacheDir, "genre-queue-${UUID.randomUUID()}.bin")
    private val queueStates = CopyOnWriteArrayList<QueueState?>()

    private val piPlayer = controller(context.packageName, Process.myUid())
    private val systemUi = controller("com.android.systemui", Process.SYSTEM_UID)

    @Before
    fun createSession() {
        runBlocking { Library.sync(context).awaitIdle() }
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PlaybackStateStore(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { stateFile }), queueFile)
        callback = PlaybackSessionCallback(context, store) { queueStates += it }
        session = onMain {
            MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).setId("genre-test").build()
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
        queueFile.delete()
    }

    private fun MediaItemsWithStartPosition.titles() = mediaItems.map { it.mediaMetadata.title.toString() }

    private fun songs(query: SongQuery) = Library.read(context) { LibraryLists(it).songs(query) }

    @Test
    fun aGenresRequestPlaysItFromTheTappedSong() {
        for ((query, titles) in listOf(
            SongQuery.genre("rock") to listOf("Bravo", "Alpha"),
            SongQuery.genre("pop").copy(sort = SongSort.TITLE, reversed = true) to listOf("Charlie", "Alpha"),
            SongQuery.genre(GenreKeys.NONE) to listOf("Delta"),
        )) {
            val songs = songs(query)
            assertThat(songs.map { it.title }).describedAs("$query").isEqualTo(titles)
            val row = titles.lastIndex
            val item = PlaybackTesting.compactItem(QueueRequest(songs[row].id, row, query), songs[row].title)

            val set = onMain { callback.onSetMediaItems(session, piPlayer, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }
                .get(30, TimeUnit.SECONDS)

            assertThat(set.titles()).describedAs("$query").isEqualTo(titles)
            assertThat(set.startIndex).describedAs("$query").isEqualTo(row)
        }
    }

    @Test
    fun aSavedQueueOfAGenreWhoseKeyIsGoneFollowsItsSongToItsFirstGenre() = runBlocking<Unit> {
        val alpha = songs(SongQuery.genre("rock")).single { it.title == "Alpha" }
        store.write(snapshot(QueueRequest(alpha.id, 0, SongQuery.genre("gone genre")), alpha.id))

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }.get(30, TimeUnit.SECONDS)

        assertThat(result.titles()).containsExactly("Bravo", "Alpha")
        assertThat(result.mediaItems[result.startIndex].mediaMetadata.title.toString()).isEqualTo("Alpha")
        PlaybackTesting.eventually("the followed genre is the queue's list") {
            queueStates.lastOrNull()?.library?.list == SongQuery.genre("rock")
        }
    }

    @Test
    fun aSavedQueueOfAGenreWhoseKeyIsGoneFollowsASongWithoutGenreToNone() = runBlocking<Unit> {
        val delta = songs(SongQuery.genre(GenreKeys.NONE)).single()
        store.write(snapshot(QueueRequest(delta.id, 0, SongQuery.genre("gone genre")), delta.id))

        val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }.get(30, TimeUnit.SECONDS)

        assertThat(result.titles()).containsExactly("Delta")
        PlaybackTesting.eventually("the songs without a genre are the queue's list") {
            queueStates.lastOrNull()?.library?.list == SongQuery.genre(GenreKeys.NONE)
        }
    }

    private fun snapshot(queue: QueueRequest, songId: Long) = PlaybackSnapshot(
        queue = queue,
        songId = songId,
        row = 0,
        positionMs = 0,
        shuffle = false,
        repeatMode = PlaybackSnapshot.REPEAT_OFF,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000,
        uri = null,
        libraryId = Library.trackRefs(context).libraryId(),
    )

    private fun controller(packageName: String, uid: Int) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        packageName,
        /* pid= */ 0,
        uid,
        MediaLibraryInfo.VERSION_INT,
        /* interfaceVersion= */ 1,
        /* trusted= */ true,
        Bundle.EMPTY,
        /* isPackageNameVerified= */ true,
    )
}
