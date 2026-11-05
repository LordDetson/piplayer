package io.github.lorddetson.piplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
