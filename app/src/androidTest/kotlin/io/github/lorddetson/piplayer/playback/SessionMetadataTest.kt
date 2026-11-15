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
 * merged values without touching playback (TI §7.1, docs/playback.md §2.5).
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

    @Test
    fun theStreamsCoverStaysUntilTheCoverResolverArrives() {
        playFirst(item(1, tagged))
        waitForTheStreamTags()

        eventually("the cover has arrived") { onMain { session.mediaMetadata.artworkData != null } }
    }

    @Test
    fun anOwnCoverReplacesTheStreamsAndListenersHearIt() {
        val heard = mutableListOf<MediaMetadata>()
        onMain {
            session.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    heard += mediaMetadata
                }
            })
        }
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the stream's cover has arrived") { onMain { session.mediaMetadata.artworkData != null } }
        val stream = onMain { session.mediaMetadata.artworkData }
        val own = io.github.lorddetson.piplayer.ui.player.TestImages.png(32)

        onMain { session.setArtwork("1", own) }

        onMain {
            assertThat(session.mediaMetadata.artworkData).isEqualTo(own)
            assertThat(heard.last().artworkData).isEqualTo(own)
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("Item title")
            // Another item's cover changes nothing now.
            session.setArtwork("2", io.github.lorddetson.piplayer.ui.player.TestImages.png(16))
            assertThat(session.mediaMetadata.artworkData).isEqualTo(own)
            session.setArtwork("1", null)
            assertThat(session.mediaMetadata.artworkData).isEqualTo(stream)
            assertThat(heard.last().artworkData).isEqualTo(stream)
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
    fun aChangeOfOwnCoversLooksAgainAndAFailureKeepsTheStreamsPicture() {
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
        var picture: ByteArray? = io.github.lorddetson.piplayer.ui.player.TestImages.png(8)
        var failing = false
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the stream's cover has arrived") { onMain { exo.mediaMetadata.artworkData != null } }
        val stream = onMain { exo.mediaMetadata.artworkData }
        val covers = sessionCovers(changes) { if (failing) throw IllegalStateException("closed") else picture }
        try {
            eventually("the own cover shows") { onMain { session.mediaMetadata.artworkData.contentEquals(picture) } }

            picture = null
            onMain { changes.value = 1 }
            eventually("the stream's picture is back") { onMain { session.mediaMetadata.artworkData.contentEquals(stream) } }

            failing = true
            onMain { changes.value = 2 }
            Thread.sleep(500)
            onMain { assertThat(session.mediaMetadata.artworkData).isEqualTo(stream) }
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
    fun thePlayingItemCarriesItsCoverUriNextToTheStreamsPicture() {
        onMain { session.artworkUriOf = { id, _ -> Uri.parse("content://covers.test/s/$id/v1") } }
        playFirst(item(1, "playback/cover-front.mp3"))
        eventually("the stream's cover has arrived") { onMain { session.mediaMetadata.artworkData != null } }

        onMain {
            assertThat(session.mediaMetadata.artworkUri).isEqualTo(Uri.parse("content://covers.test/s/1/v1"))
            assertThat(session.mediaMetadata.artworkData).isEqualTo(exo.mediaMetadata.artworkData)
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
        eventually("the stream's cover has arrived") { onMain { session.mediaMetadata.artworkData != null } }
        val uris = io.github.lorddetson.piplayer.covers.Covers.uris(context)
        val song = io.github.lorddetson.piplayer.covers.CoverRequest.Track(1)
        val stream = onMain { session.mediaMetadata.artworkUri!! }
        val bare = ProviderArtwork.ofQueueItem(context, "1")!!

        onMain { session.setArtwork("1", io.github.lorddetson.piplayer.ui.player.TestImages.png(32)) }
        val own = onMain { session.mediaMetadata.artworkUri!! }
        onMain { session.setArtwork("1", null) }
        val back = onMain { session.mediaMetadata.artworkUri!! }

        assertThat(listOf(stream, own, bare).toSet()).hasSize(3)
        assertThat(back).isEqualTo(stream)
        for (uri in listOf(stream, own, bare)) assertThat(uris.parse(uri.authority, uri.pathSegments)).describedAs("$uri").isEqualTo(song)
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

    @Test
    fun theCurrentAndTheNextItemFollowTheMergedValuesAndTheOthersWait() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        val refresher = SessionItemRefresher(session) { id -> song(id, "New $id") }

        onMain {
            refresher.refresh()
            assertThat(session.getMediaItemAt(0).mediaMetadata.title.toString()).isEqualTo("New 1")
            assertThat(session.getMediaItemAt(1).mediaMetadata.title.toString()).isEqualTo("New 2")
            assertThat(session.getMediaItemAt(2).mediaMetadata.title.toString()).isEqualTo("Three")
            assertThat(session.mediaMetadata.title.toString()).isEqualTo("New 1")
        }
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
            SessionItemRefresher(session) { song(it, "One") }.refresh()
            assertThat(timelines).isZero()
        }
    }

    @Test
    fun anItemTheLibraryDoesNotKnowIsLeftAlone() {
        playFirst(item(1, LONG_FIXTURE, "One"))

        onMain {
            SessionItemRefresher(session) { null }.refresh()
            assertThat(session.getMediaItemAt(0).mediaMetadata.title.toString()).isEqualTo("One")
        }
    }

    @Test
    fun aTransitionBringsTheNewNextItemUpToDate() {
        playFirst(item(1, LONG_FIXTURE, "One"), item(2, LONG_FIXTURE, "Two"), item(3, LONG_FIXTURE, "Three"))
        onMain { SessionItemRefresher(session) { id -> song(id, "New $id") }.start() }

        onMain { session.seekToNextMediaItem() }

        eventually("the new next item is up to date") {
            onMain { session.getMediaItemAt(2).mediaMetadata.title.toString() == "New 3" }
        }
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

        onMain { SessionItemRefresher(session) { song(it, "New") }.refresh() }
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
