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
import io.github.lorddetson.piplayer.library.db.TrackEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.library.lists.MissingReasons
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.TrackTags
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.UserData
import java.io.File
import java.util.UUID
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
 * The smart lists as queue requests (docs/library.md §17.3, docs/playback.md §2.3): a request of each plays its list from
 * the tapped song, and a saved queue of one comes back from its request after the process died. The library is the app's,
 * filled from a test index; the callback is called directly, as in [LibraryTreePlayTest].
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SmartListPlaybackTest {

    private val context = TestFiles.targetContext

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", dateAdded = 1_700_000_003),
            row(2, "Bravo", dateAdded = 1_700_000_001),
            row(3, "Charlie", dateAdded = 1_700_000_002),
            row(4, "Delta", dateAdded = 1_700_000_004),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var callback: PlaybackSessionCallback
    private lateinit var saver: PlaybackStateSaver
    private lateinit var store: PlaybackStateStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var session: MediaLibrarySession
    private val stateFile = File(context.cacheDir, "smart-state-${UUID.randomUUID()}.preferences_pb")
    private val queueFile = File(context.cacheDir, "smart-queue-${UUID.randomUUID()}.bin")

    private val piPlayer = controller(context.packageName, Process.myUid())
    private val systemUi = controller("com.android.systemui", Process.SYSTEM_UID)

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun identity(n: Long) = checkNotNull(UserData.database(context).identities().idOfKey(Hex.bytes(key(n))))

    /**
     * Every song has an identity; Bravo and Delta are favorites; Charlie was played twice and Alpha once, later; Delta is
     * read without an album.
     */
    @Before
    fun createSession() = runBlocking {
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        for (n in 1L..4L) dao.setContent(fileId(n), key(n), 200_000, DurationSource.HEADER)
        Library.identities(context).attach()
        UserData.favorites(context).set(identity(2), favorite = true)
        UserData.favorites(context).set(identity(4), favorite = true)
        UserData.plays(context).record(identity(3), startedAt = 100, listenedMs = 1)
        UserData.plays(context).record(identity(3), startedAt = 200, listenedMs = 1)
        UserData.plays(context).record(identity(1), startedAt = 300, listenedMs = 1)
        val delta = fileId(4)
        dao.addTrack(TrackEntity.of(delta, TrackTags.from(mapOf("TITLE" to listOf("Delta"), "ARTIST" to listOf("Dee")))))
        dao.markRead(delta, at = 1, readerVersion = 1, format = null, codec = null, sampleRate = null, bitDepth = null,
            channels = null, bitrate = null, tagEncoding = null, coverPresent = true, coverBytes = null, tagLengthMs = null)
        TrackDisplayWriter(Library.database(context)).refresh(listOf(delta))
        Library.mirror(context).mirror()

        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PlaybackStateStore(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { stateFile }), queueFile)
        val player = onMain { ExoPlayer.Builder(context).build() }
        // The real saver, so that a test sees what a queue saves (docs/playback.md §4.8).
        saver = PlaybackStateSaver(player, store)
        callback = PlaybackSessionCallback(context, store) { saver.setQueue(it) }
        session = onMain {
            saver.start()
            MediaLibrarySession.Builder(context, player, callback).setId("smart-test").build()
        }
    }

    @After
    fun releaseSession() {
        onMain {
            val player = session.player
            saver.stop()
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

    private fun MediaItemsWithStartPosition.current() = mediaItems[startIndex].mediaMetadata.title.toString()

    /** The titles of [query]'s list as the app reads it. */
    private fun listed(query: SongQuery) = Library.read(context) { LibraryLists(it).songs(query) }.map { it.title }

    private val expected = mapOf(
        SongQuery.favorites() to listOf("Bravo", "Delta"),
        SongQuery.recentlyAdded() to listOf("Delta", "Alpha", "Charlie", "Bravo"),
        SongQuery.recentlyPlayed() to listOf("Alpha", "Charlie"),
        SongQuery.mostPlayed() to listOf("Charlie", "Alpha"),
        SongQuery.missingInfo() to listOf("Delta"),
    )

    @Test
    fun aRequestOfEachSmartListPlaysItFromTheTappedSong() = runBlocking<Unit> {
        assertThat(expected.keys.map { it.kind }).containsExactlyElementsOf(ListKind.SMART)
        for ((query, titles) in expected) {
            assertThat(listed(query)).describedAs("$query").isEqualTo(titles)
            val row = titles.lastIndex
            val song = Library.read(context) { LibraryLists(it).songs(query) }[row]
            val item = PlaybackTesting.compactItem(QueueRequest(song.id, row, query), song.title)

            val set = onMain { callback.onSetMediaItems(session, piPlayer, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }
                .get(30, TimeUnit.SECONDS)

            assertThat(set.titles()).describedAs("$query").isEqualTo(titles)
            assertThat(set.current()).describedAs("$query").isEqualTo(titles.last())
        }
    }

    @Test
    fun aSavedQueueOfASmartListComesBackFromItsRequest() = runBlocking<Unit> {
        for (query in listOf(SongQuery.recentlyPlayed(), SongQuery.favorites().copy(reversed = true), SongQuery.missingInfo(MissingReasons.ALL))) {
            val songs = Library.read(context) { LibraryLists(it).songs(query) }
            val current = songs.first()
            store.write(snapshot(QueueRequest(current.id, 0, query), current.id))

            val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }.get(30, TimeUnit.SECONDS)

            assertThat(result.titles()).describedAs("$query").isEqualTo(songs.map { it.title })
            assertThat(result.startIndex).describedAs("$query").isZero()
        }
    }

    /**
     * Recently played and Most played change as their own queue plays (docs/library.md §17.3): a restored queue keeps the
     * songs and order it started with, so that it goes on with the songs not yet heard. Recently played is [Alpha, Charlie]
     * and Most played [Charlie, Alpha]; the tapped song is counted again and moves up before the process dies.
     */
    @Test
    fun aRestoredRecentlyPlayedOrMostPlayedQueueKeepsTheOrderItStartedWith() = runBlocking<Unit> {
        for ((query, plays) in listOf(SongQuery.recentlyPlayed() to 1, SongQuery.mostPlayed() to 3)) {
            val songs = Library.read(context) { LibraryLists(it).songs(query) }
            val song = songs[1]
            val item = PlaybackTesting.compactItem(QueueRequest(song.id, 1, query), song.title)
            val set = onMain { callback.onSetMediaItems(session, piPlayer, listOf(item), C.INDEX_UNSET, C.TIME_UNSET) }
                .get(30, TimeUnit.SECONDS)
            SavedStates(store).use { saved ->
                // As Media3 sets what the callback gives it; the saver writes the queue.
                onMain { session.player.setMediaItems(set.mediaItems, set.startIndex, 0) }
                saved.await("the queue of $query is saved") { it?.songId == song.id }
            }
            val identity = identity(song.title.let { title -> listOf("Alpha", "Bravo", "Charlie", "Delta").indexOf(title) + 1L })
            repeat(plays) { UserData.plays(context).record(identity, startedAt = 1_000L + it, listenedMs = 1) }
            Library.mirror(context).mirror()
            assertThat(listed(query).first()).describedAs("$query after the play").isEqualTo(song.title)
            onMain { session.player.clearMediaItems() }

            val result = onMain { callback.onPlaybackResumption(session, systemUi, isForPlayback = true) }.get(30, TimeUnit.SECONDS)

            assertThat(result.titles()).describedAs("$query").isEqualTo(songs.map { it.title })
            assertThat(result.startIndex).describedAs("$query").isEqualTo(1)
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
