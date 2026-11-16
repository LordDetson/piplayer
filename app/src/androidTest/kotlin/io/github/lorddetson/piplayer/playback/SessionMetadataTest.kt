package io.github.lorddetson.piplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.playback.PlaybackTesting.LONG_FIXTURE
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the session shows is the queue item's metadata, not what the extractor finds in the stream, and it follows the
 * merged values without touching playback (TI §7.1, docs/playback.md §2.6).
 */
@RunWith(AndroidJUnit4::class)
class SessionMetadataTest {

    private val context = TestFiles.targetContext
    private val exo: ExoPlayer = onMain { PlaybackPlayer.builder(context).build() }
    private val session = onMain { SessionPlayer(exo) }
    private val tagged = "tagging/id3v23.mp3"

    @After
    fun release() = onMain { exo.release() }

    private fun item(id: Long, asset: String, title: String? = "Item title", artist: String? = null) = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(Uri.fromFile(TestFiles.copy(asset)))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
        .build()

    private fun playFirst(vararg items: MediaItem) = onMain {
        session.volume = 0f
        session.setMediaItems(items.toList())
        session.prepare()
        session.play()
    }

    private fun waitForTheStreamTags() =
        eventually("the player has read the stream's tags") { onMain { exo.mediaMetadata.artist != null } }

    @Test
    fun theSessionShowsTheItemNotTheStream() {
        playFirst(item(1, tagged))
        waitForTheStreamTags()

        onMain {
            assertThat(exo.mediaMetadata.artist.toString()).describedAs("the stream's tag").isEqualTo("The Quiet Harbors")
            assertThat(session.mediaMetadata.artist).describedAs("what the session shows").isNull()
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("Item title")
            assertThat(session.mediaMetadata.albumTitle).isNull()
        }
    }

    @Test
    fun aListenerHearsTheItemNotTheStream() {
        val heard = mutableListOf<MediaMetadata>()
        onMain {
            session.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    heard += mediaMetadata
                }
            })
        }
        playFirst(item(1, tagged))
        waitForTheStreamTags()

        eventually("the listener has heard something") { onMain { heard.isNotEmpty() } }
        onMain {
            assertThat(heard).allSatisfy { assertThat(it.artist).isNull() }
            assertThat(heard.last().title.toString()).isEqualTo("Item title")
        }
    }

    @Test
    fun aRemovedListenerHearsNothingMore() {
        var calls = 0
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                calls++
            }
        }
        onMain {
            session.addListener(listener)
            session.removeListener(listener)
        }
        playFirst(item(1, tagged))
        waitForTheStreamTags()

        onMain { assertThat(calls).isZero() }
    }

    /**
     * The picture the extractor reads from the stream is never shown for a library song: the embedded picture is a step
     * of the cover order, which the service resolves (TI §7.1, docs/library.md §13.6). Replaces the M2a check that the
     * stream's cover stayed until the cover resolver arrived.
     */
    @Test
    fun aLibrarySongShowsNoPictureOfTheStream() {
        val added = item(1, "playback/cover-front.mp3").buildUpon().setMediaId(QueueIds.added(1, fileId = 7)).build()
        playFirst(item(1, "playback/cover-front.mp3"), added)
        eventually("the player has read the stream's cover") { onMain { exo.mediaMetadata.artworkData != null } }

        onMain {
            assertThat(session.mediaMetadata.artworkData).describedAs("a library song").isNull()
            session.seekToNextMediaItem()
        }
        eventually("the player has read the added entry's cover") { onMain { exo.mediaMetadata.artworkData != null } }
        onMain { assertThat(session.mediaMetadata.artworkData).describedAs("an added entry").isNull() }
    }

    @Test
    fun anOwnCoverShowsAndListenersHearIt() {
        val heard = mutableListOf<MediaMetadata>()
        onMain {
            session.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    heard += mediaMetadata
                }
            })
        }
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the player has read the stream's cover") { onMain { exo.mediaMetadata.artworkData != null } }
        val own = io.github.lorddetson.piplayer.ui.player.TestImages.png(32)

        onMain { session.setArtwork("1", own) }

        onMain {
            assertThat(session.mediaMetadata.artworkData).isEqualTo(own)
            assertThat(heard.last().artworkData).isEqualTo(own)
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("Item title")
            // Another item's cover changes nothing now.
            session.setArtwork("2", io.github.lorddetson.piplayer.ui.player.TestImages.png(16))
            assertThat(session.mediaMetadata.artworkData).isEqualTo(own)
            // Without a cover the song shows none, not the stream's picture.
            session.setArtwork("1", null)
            assertThat(session.mediaMetadata.artworkData).isNull()
            assertThat(heard.last().artworkData).isNull()
        }
    }

    @Test
    fun aCoverForAFileWithoutAPictureShowsToo() {
        playFirst(item(1, LONG_FIXTURE))
        eventually("playback has started") { onMain { exo.playbackState == Player.STATE_READY } }
        onMain { assertThat(session.mediaMetadata.artworkData).isNull() }
        val own = io.github.lorddetson.piplayer.ui.player.TestImages.png(32)

        onMain { session.setArtwork("1", own) }

        onMain { assertThat(session.mediaMetadata.artworkData).isEqualTo(own) }
    }

    private fun sessionCovers(changes: kotlinx.coroutines.flow.Flow<Long>, resolve: suspend (Long) -> ByteArray?) = onMain {
        SessionCovers(session, resolve, changes, kotlinx.coroutines.MainScope()).also { it.start() }
    }

    @Test
    fun theServiceGivesEachLibrarySongItsPictureAtItsTransition() {
        val pictures = mapOf(1L to io.github.lorddetson.piplayer.ui.player.TestImages.png(8), 2L to io.github.lorddetson.piplayer.ui.player.TestImages.png(16))
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"))
        val covers = sessionCovers(kotlinx.coroutines.flow.MutableStateFlow(0L)) { pictures[it] }
        try {
            eventually("the first song has its picture") { onMain { session.mediaMetadata.artworkData.contentEquals(pictures[1]) } }

            onMain { session.seekToNextMediaItem() }

            eventually("the second song has its picture") { onMain { session.mediaMetadata.artworkData.contentEquals(pictures[2]) } }
        } finally {
            onMain { covers.stop() }
        }
    }

    @Test
    fun aChangeOfCoversLooksAgainAndAFailureKeepsThePictureShown() {
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val first = io.github.lorddetson.piplayer.ui.player.TestImages.png(8)
        val second = io.github.lorddetson.piplayer.ui.player.TestImages.png(16)
        var picture: ByteArray? = first
        var failing = false
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the player has read the stream's cover") { onMain { exo.mediaMetadata.artworkData != null } }
        val covers = sessionCovers(changes) { if (failing) throw IllegalStateException("closed") else picture }
        try {
            eventually("the cover shows") { onMain { session.mediaMetadata.artworkData.contentEquals(first) } }

            // The resolver finds no cover: the song shows none, not the stream's picture.
            picture = null
            onMain { changes.value = 1 }
            eventually("the cover is gone") { onMain { session.mediaMetadata.artworkData == null } }

            picture = second
            onMain { changes.value = 2 }
            eventually("the new cover shows") { onMain { session.mediaMetadata.artworkData.contentEquals(second) } }

            failing = true
            onMain { changes.value = 3 }
            Thread.sleep(500)
            onMain { assertThat(session.mediaMetadata.artworkData).isEqualTo(second) }
        } finally {
            onMain { covers.stop() }
        }
    }

    @Test
    fun aFileOpenedFromAnotherAppKeepsTheStreamsPicture() {
        val opened = MediaItem.Builder()
            .setMediaId("content://test/opened")
            .setUri(Uri.fromFile(TestFiles.copy("playback/cover-front.mp3")))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Opened").build())
            .build()
        var asked = 0
        playFirst(opened)
        val covers = sessionCovers(kotlinx.coroutines.flow.MutableStateFlow(0L)) { asked++; io.github.lorddetson.piplayer.ui.player.TestImages.png(8) }
        try {
            eventually("the stream's cover has arrived") { onMain { session.mediaMetadata.artworkData != null } }
            onMain {
                assertThat(session.mediaMetadata.artworkData).isEqualTo(exo.mediaMetadata.artworkData)
                assertThat(asked).isZero()
            }
        } finally {
            onMain { covers.stop() }
        }
    }

    @Test
    fun thePlayingItemCarriesItsCoverUriNextToItsPicture() {
        onMain { session.artworkUriOf = { id, _ -> Uri.parse("content://covers.test/s/$id/v1") } }
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the player has read the stream's cover") { onMain { exo.mediaMetadata.artworkData != null } }
        val own = io.github.lorddetson.piplayer.ui.player.TestImages.png(32)

        onMain { session.setArtwork("1", own) }

        onMain {
            assertThat(session.mediaMetadata.artworkUri).isEqualTo(Uri.parse("content://covers.test/s/1/v1"))
            assertThat(session.mediaMetadata.artworkData).isEqualTo(own)
            // The queue's items stay without artwork.
            assertThat(session.currentMediaItem!!.mediaMetadata.artworkUri).isNull()
        }
    }

    @Test
    fun anItemWithoutAPictureCarriesItsCoverUriAnAddedEntryItsSongsAndAnOpenedFileNone() {
        // The service's own mapping (docs/library.md §13.8.6).
        onMain { session.artworkUriOf = { id, picture -> ProviderArtwork.ofQueueItem(context, id, picture) } }
        val opened = MediaItem.Builder()
            .setMediaId("content://test/opened")
            .setUri(Uri.fromFile(TestFiles.copy(LONG_FIXTURE)))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Opened").build())
            .build()
        val added = item(1, LONG_FIXTURE).buildUpon().setMediaId(QueueIds.added(1, fileId = 7)).build()
        playFirst(item(1, LONG_FIXTURE), added, opened)
        eventually("playback has started") { onMain { exo.playbackState == Player.STATE_READY } }
        val uris = io.github.lorddetson.piplayer.covers.Covers.uris(context)
        fun named(uri: Uri?) = uri?.let { uris.parse(it.authority, it.pathSegments) }

        onMain {
            assertThat(named(session.mediaMetadata.artworkUri)).isEqualTo(io.github.lorddetson.piplayer.covers.CoverRequest.Track(1))
            assertThat(session.mediaMetadata.artworkData).isNull()
            session.seekToNextMediaItem()
            assertThat(named(session.mediaMetadata.artworkUri)).isEqualTo(io.github.lorddetson.piplayer.covers.CoverRequest.Track(7))
            session.seekToNextMediaItem()
            assertThat(session.currentMediaItem!!.mediaId).isEqualTo("content://test/opened")
            assertThat(session.mediaMetadata.artworkUri).isNull()
        }
    }

    @Test
    fun theCoverUriChangesWithThePictureTheSessionShows() {
        // Media3's bitmap cache matches by URI before data: one URI for two pictures would keep the first one.
        onMain { session.artworkUriOf = { id, picture -> ProviderArtwork.ofQueueItem(context, id, picture) } }
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the player has read the stream's cover") { onMain { exo.mediaMetadata.artworkData != null } }
        val uris = io.github.lorddetson.piplayer.covers.Covers.uris(context)
        val song = io.github.lorddetson.piplayer.covers.CoverRequest.Track(1)
        val bare = onMain { session.mediaMetadata.artworkUri!! }

        onMain { session.setArtwork("1", io.github.lorddetson.piplayer.ui.player.TestImages.png(32)) }
        val first = onMain { session.mediaMetadata.artworkUri!! }
        onMain { session.setArtwork("1", io.github.lorddetson.piplayer.ui.player.TestImages.png(16)) }
        val second = onMain { session.mediaMetadata.artworkUri!! }
        onMain { session.setArtwork("1", null) }
        val back = onMain { session.mediaMetadata.artworkUri!! }

        assertThat(bare).describedAs("the stream's picture names no URI of its own").isEqualTo(ProviderArtwork.ofQueueItem(context, "1"))
        assertThat(listOf(bare, first, second).toSet()).hasSize(3)
        assertThat(back).isEqualTo(bare)
        for (uri in listOf(bare, first, second)) assertThat(uris.parse(uri.authority, uri.pathSegments)).describedAs("$uri").isEqualTo(song)
    }

    @Test
    fun listenersHearANewCoverUriEvenWithTheSamePicture() {
        var version = 1
        val heard = mutableListOf<MediaMetadata>()
        onMain {
            session.artworkUriOf = { id, _ -> Uri.parse("content://covers.test/s/$id/v$version") }
            session.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    heard += mediaMetadata
                }
            })
        }
        playFirst(item(1, LONG_FIXTURE))
        eventually("playback has started") { onMain { exo.playbackState == Player.STATE_READY } }
        onMain { heard.clear() }
        // At a transition the session looks for the picture again; with nothing new that is no news.
        onMain { session.setArtwork("1", null) }
        onMain { assertThat(heard).isEmpty() }
        version = 2

        // What SessionCovers does when own covers change: the picture is looked for again, and it is still none.
        onMain { session.setArtwork("1", null) }

        onMain {
            assertThat(session.mediaMetadata.artworkUri).isEqualTo(Uri.parse("content://covers.test/s/1/v2"))
            assertThat(heard.map { it.artworkUri }).containsExactly(Uri.parse("content://covers.test/s/1/v2"))
            // The same again is no news.
            session.setArtwork("1", null)
            assertThat(heard).hasSize(1)
        }
    }

    @Test
    fun theListenerWrapperPassesOnEveryEvent() {
        val forwarded = ForwardingListener::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()

        val missing = Player.Listener::class.java.methods.map { it.name to it.parameterTypes.toList() }.filter { it !in forwarded }

        assertThat(missing).isEmpty()
    }

    @Test
    fun anEmptyPlayerShowsNothing() {
        onMain { assertThat(session.mediaMetadata).isEqualTo(MediaMetadata.EMPTY) }
    }

    private fun song(id: Long, title: String) = Song(id, "uri$id", title, null, null, null)

    /** The player's thread, on which a read that does not wait applies at once. */
    private val mainScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    /** A refresher whose library gives each file the song [of] makes, reading each time; [reads] lists what it was asked. */
    private fun refresher(reads: MutableList<Collection<Long>> = mutableListOf(), of: (Long) -> Song?) =
        SessionItemRefresher(session, { ids -> reads += ids.toList(); ids.mapNotNull { id -> of(id)?.let { id to it } }.toMap() }, mainScope)

    private fun titles() = onMain { List(session.mediaItemCount) { session.getMediaItemAt(it).mediaMetadata.title.toString() } }

    @Test
    fun theCurrentAndTheNextItemFollowTheMergedValuesAndTheOthersWait() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        val reads = mutableListOf<Collection<Long>>()
        val refresher = refresher(reads) { id -> song(id, "New $id") }

        onMain { refresher.refresh() }

        assertThat(titles()).containsExactly("New 1", "New 2", "Three")
        onMain { assertThat(session.mediaMetadata.title.toString()).isEqualTo("New 1") }
        assertThat(reads.flatten()).containsExactlyInAnyOrder(1L, 2L)
    }

    /**
     * After a change of decided values (TI §7.1, M3 step 21): the playing item and the queued items of the files it
     * touched, an added entry of them too, come up to date; the next item of another file stays as it is, even though
     * the library's values for it differ, and no item of another file is read.
     */
    @Test
    fun aDecisionRefreshesThePlayingItemAndTheQueuedItemsOfItsFilesButNotTheNextOneOfAnother() {
        val again = item(1, LONG_FIXTURE, "One again").buildUpon().setMediaId(QueueIds.added(1, fileId = 1)).build()
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), again, item(3, LONG_FIXTURE, "Three"), item(4, LONG_FIXTURE, "Four"))
        val reads = mutableListOf<Collection<Long>>()
        val refresher = refresher(reads) { id -> song(id, "New $id") }

        onMain { refresher.refreshDecided(setOf(1L, 4L)) }

        assertThat(titles()).containsExactly("New 1", "Two", "New 1", "Three", "New 4")
        assertThat(reads.flatten().toSet()).containsExactlyInAnyOrder(1L, 4L)
    }

    @Test
    fun aDecisionThatDidNotTouchThePlayingItemStillComparesIt() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        val refresher = refresher { id -> song(id, "New $id") }

        onMain { refresher.refreshDecided(setOf(3L)) }

        assertThat(titles()).containsExactly("New 1", "Two", "New 3")
    }

    @Test
    fun aDecisionWhoseFilesAreNotKnownComparesThePlayingAndTheNextItem() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        val refresher = refresher { id -> song(id, "New $id") }

        onMain { refresher.refreshDecided(null) }

        assertThat(titles()).containsExactly("New 1", "New 2", "Three")
    }

    @Test
    fun aDecisionRefreshesAtMostTwentyQueuedItemsAndTheRestAtTheirTransition() {
        val items = List(25) { item(1, LONG_FIXTURE, "Old $it").buildUpon().setMediaId(QueueIds.added(it.toLong() + 1, fileId = 1)).build() }
        playFirst(*items.toTypedArray())
        val refresher = refresher { id -> song(id, "New") }
        onMain { refresher.start() }

        onMain { refresher.refreshDecided(setOf(1L)) }

        assertThat(titles().take(21)).allMatch { it == "New" }
        assertThat(titles().drop(21)).containsExactly("Old 21", "Old 22", "Old 23", "Old 24")
        onMain { session.seekTo(20, 0) }
        eventually("the item after the twenty comes up to date at its transition") { titles()[21] == "New" }
        onMain { refresher.stop() }
    }

    @Test
    fun theValuesAreReadAgainAtEachRefreshNotKept() {
        playFirst(item(1, LONG_FIXTURE, "One"))
        var title = "Read once"
        val refresher = refresher { id -> song(id, title) }
        onMain { refresher.refreshDecided(setOf(1L)) }
        assertThat(titles()).containsExactly("Read once")

        title = "Read again"
        onMain { refresher.refreshDecided(setOf(1L)) }

        assertThat(titles()).containsExactly("Read again")
    }

    @Test
    fun anItemThatCarriesACoverUriIsReplacedWithoutOne() {
        val withCover = item(1, LONG_FIXTURE, "One").buildUpon()
            .setMediaMetadata(SessionItemRefresher.songMetadata(song(1, "One")).buildUpon().setArtworkUri(Uri.parse("content://covers.test/s/1/old")).build())
            .build()
        playFirst(withCover)
        val refresher = refresher { id -> song(id, "One") }

        onMain { refresher.refreshDecided(setOf(1L)) }

        onMain {
            assertThat(session.getMediaItemAt(0).mediaMetadata.artworkUri).isNull()
            assertThat(session.getMediaItemAt(0).mediaMetadata.title.toString()).isEqualTo("One")
        }
    }

    @Test
    fun aSlowReadIsAppliedBeforeTheNextReadStarts() {
        playFirst(item(1, LONG_FIXTURE, "One"))
        val first = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reads = java.util.concurrent.CopyOnWriteArrayList<String>()
        var calls = 0
        val refresher = SessionItemRefresher(session, { ids ->
            val call = ++calls
            reads += "start $call"
            if (call == 1) first.await()
            reads += "end $call"
            ids.associateWith { song(it, "Read $call") }
        }, mainScope)

        onMain {
            refresher.refreshDecided(setOf(1L))
            refresher.refreshDecided(setOf(1L))
        }
        assertThat(reads).containsExactly("start 1")

        onMain { first.complete(Unit) }

        eventually("both reads are applied") { reads.size == 4 && titles() == listOf("Read 2") }
        assertThat(reads).containsExactly("start 1", "end 1", "start 2", "end 2")
    }

    @Test
    fun aReadThatFailsLeavesTheItemsAsTheyAre() {
        playFirst(item(1, LONG_FIXTURE, "One"))
        val refresher = SessionItemRefresher(session, { throw IllegalStateException("closed") }, mainScope)

        onMain { refresher.refreshDecided(setOf(1L)) }

        assertThat(titles()).containsExactly("One")
    }

    @Test
    fun anItemWithTheSameValuesIsLeftAlone() {
        playFirst(item(1, LONG_FIXTURE, "One"))
        var timelines = 0
        onMain {
            exo.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) timelines++
                }
            })
        }
        onMain {
            refresher { song(it, "One") }.refresh()
            refresher { song(it, "One") }.refreshDecided(setOf(1L))
            assertThat(timelines).isZero()
        }
    }

    @Test
    fun anItemTheLibraryDoesNotKnowIsLeftAlone() {
        playFirst(item(1, LONG_FIXTURE, "One"))

        onMain {
            refresher { null }.refresh()
            refresher { null }.refreshDecided(setOf(1L))
            assertThat(session.getMediaItemAt(0).mediaMetadata.title.toString()).isEqualTo("One")
        }
    }

    @Test
    fun aTransitionBringsTheNewNextItemUpToDate() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        val refresher = refresher { id -> song(id, "New $id") }
        onMain { refresher.start() }

        onMain { session.seekToNextMediaItem() }

        eventually("the new next item is up to date") {
            onMain { session.getMediaItemAt(2).mediaMetadata.title.toString() == "New 3" }
        }
        onMain { refresher.stop() }
    }

    @Test
    fun replacingThePlayingItemDoesNotTouchPlayback() {
        val states = mutableListOf<Int>()
        playFirst(item(1, LONG_FIXTURE, "One"))
        eventually("playback has started") { onMain { exo.playbackState == Player.STATE_READY && exo.isPlaying } }
        Thread.sleep(500)
        val before = onMain {
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states += playbackState
                }
            })
            exo.currentPosition
        }

        onMain { refresher { song(it, "New") }.refreshDecided(setOf(1L)) }
        Thread.sleep(500)

        onMain {
            assertThat(states).describedAs("no new preparation").isEmpty()
            assertThat(exo.isPlaying).isTrue()
            assertThat(exo.currentPosition).isGreaterThan(before)
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("New")
        }
    }

    /** What the player does for the playing item while a replacement lands (docs/playback.md §8.1). */
    @OptIn(UnstableApi::class)
    private class Preparations : AnalyticsListener, Player.Listener {
        val loads = AtomicInteger()
        val decoders = AtomicInteger()
        val outputs = AtomicInteger()
        val states = CopyOnWriteArrayList<Int>()
        val transitions = CopyOnWriteArrayList<Int>()
        val discontinuities = CopyOnWriteArrayList<Int>()

        override fun onLoadStarted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            retryCount: Int,
        ) {
            loads.incrementAndGet()
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            decoders.incrementAndGet()
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
            outputs.incrementAndGet()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            states += playbackState
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transitions += reason
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            discontinuities += reason
        }

        /** Nothing of the item was loaded, decoded or put out again, and playback went on as it was. */
        val none: Boolean
            get() = loads.get() == 0 && decoders.get() == 0 && outputs.get() == 0 && states.isEmpty() &&
                transitions.isEmpty() && discontinuities.isEmpty()

        override fun toString() =
            "loads $loads, decoders $decoders, audio outputs $outputs, states $states, transitions $transitions, " +
                "discontinuities $discontinuities"
    }

    /** Plays [first] through the session player until it is well under way, then watches what the player does. */
    @OptIn(UnstableApi::class)
    private fun playingAndWatched(first: MediaItem): Pair<Preparations, Long> {
        playFirst(first)
        eventually("playback has started") { onMain { exo.playbackState == Player.STATE_READY && exo.isPlaying } }
        Thread.sleep(500)
        val watched = Preparations()
        val before = onMain {
            exo.addAnalyticsListener(watched)
            exo.addListener(watched)
            exo.currentPosition
        }
        return watched to before
    }

    /**
     * The refresh of the playing item after a decision (TI §7.1, M3 step 21): new title, artist, album, length and cover
     * URI for the same file. Media3 swaps the item without preparing it again: no new load, decoder or audio output, no
     * state change, transition or jump, and the position runs on. Checked on every Media3 version PiPlayer moves to.
     */
    @OptIn(UnstableApi::class)
    @Test
    fun newMetadataForThePlayingItemDoesNotPrepareItAgain() {
        val first = item(1, LONG_FIXTURE, "One")
        val (watched, before) = playingAndWatched(first)
        val periodBefore = onMain { exo.currentTimeline.getUidOfPeriod(exo.currentPeriodIndex) }
        val cover = Uri.parse("content://io.github.lorddetson.piplayer.test/cover/1")

        onMain {
            session.replaceMediaItem(
                0,
                first.buildUpon().setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Decided")
                        .setArtist("Decided artist")
                        .setAlbumTitle("Decided album")
                        .setDurationMs(123_456)
                        .setArtworkUri(cover)
                        .build(),
                ).build(),
            )
        }
        Thread.sleep(1_000)

        onMain {
            assertThat(watched.none).describedAs("no new preparation: $watched").isTrue()
            assertThat(exo.currentTimeline.getUidOfPeriod(exo.currentPeriodIndex)).describedAs("the same period")
                .isEqualTo(periodBefore)
            assertThat(exo.isPlaying).isTrue()
            assertThat(exo.currentPosition).isGreaterThan(before + 500)
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("Decided")
            assertThat(session.mediaMetadata.artworkUri).isEqualTo(cover)
            assertThat(session.currentMediaItem?.mediaMetadata?.durationMs).isEqualTo(123_456)
        }
    }

    /** The control of the test above: another file in the playing item's place is loaded and decoded anew. */
    @OptIn(UnstableApi::class)
    @Test
    fun anotherFileInThePlayingItemsPlaceIsPreparedAgain() {
        val first = item(1, LONG_FIXTURE, "One")
        val other = TestFiles.copy(LONG_FIXTURE).copyTo(File(context.cacheDir, "other-file.mp3"), overwrite = true)
        val (watched, _) = playingAndWatched(first)

        onMain { session.replaceMediaItem(0, first.buildUpon().setUri(Uri.fromFile(other)).build()) }

        eventually("the other file is loaded") { onMain { watched.loads.get() > 0 } }
    }
}
