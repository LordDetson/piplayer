package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.media.browse.MediaBrowser
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.play
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers embedded in the files reach the controllers and the platform session (docs/playback.md §3.4). */
@RunWith(AndroidJUnit4::class)
class CoverArtTest {

    @get:Rule
    val songs = SongSourceRule()

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    /** Plays [asset] and returns the color and size of its cover once the player has read it. */
    private fun coverOf(asset: String): Pair<Int, Int> {
        controller.play(asset)
        controller.waitUntil("$asset has a cover") { mediaMetadata.artworkData != null }
        val bytes = onMain { controller.mediaMetadata.artworkData }!!
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) to bitmap.width
    }

    @Test
    fun coversOfEveryTaggedFormatReachTheController() {
        listOf("cover-front.mp3", "cover-front.flac", "cover-front.m4a", "cover-front.opus", "cover-front.ogg").forEach {
            assertThat(coverOf("playback/$it")).describedAs(it).isEqualTo(FRONT_COLOR to 64)
        }
    }

    @Test
    fun theFrontCoverWinsOverABackCover() {
        assertThat(coverOf("playback/cover-back-then-front.mp3")).isEqualTo(FRONT_COLOR to 64)
        assertThat(coverOf("playback/cover-front-then-back.flac")).isEqualTo(FRONT_COLOR to 64)
    }

    @Test
    fun aFileWithoutACoverHasNone() {
        controller.play("playback/tagged-44100-stereo.mp3")

        controller.waitUntil("its tracks are known") { currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) }
        assertThat(onMain { controller.mediaMetadata.artworkData }).isNull()
    }

    @Test
    fun theQueueNamesTheSongEvenWhenItsTagsSayOtherwise() {
        songs.songs = listOf(SongSourceRule.song(1, "Alpha", "playback/tagged-44100-stereo.mp3"))

        controller.tap(QueueRequest(startSongId = 1, startRow = 0))

        controller.waitUntil("the song plays with its tracks known") {
            playbackState == Player.STATE_READY && currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
        }
        assertThat(onMain { controller.mediaMetadata.title.toString() }).isEqualTo("Alpha")
        assertThat(onMain { controller.mediaMetadata.albumTitle.toString() }).isEqualTo("Album 1")
    }

    @Test
    fun thePlatformSessionGetsTitleArtistAlbumAndCover() {
        songs.songs = listOf(SongSourceRule.song(1, "Alpha", "playback/cover-front.mp3"))
        controller.tap(QueueRequest(startSongId = 1, startRow = 0))
        controller.waitUntil("Alpha plays") { isPlaying }

        val context = TestFiles.targetContext
        val connected = CountDownLatch(1)
        val browser = onMain {
            MediaBrowser(context, ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                }, null).also { it.connect() }
        }
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
            val platform = onMain { android.media.session.MediaController(context, browser.sessionToken) }
            eventually("the platform session has the cover") {
                onMain { platform.metadata?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) } != null
            }
            val metadata = onMain { platform.metadata }!!
            assertThat(metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)).isEqualTo("Alpha")
            assertThat(metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)).isEqualTo("Artist 1")
            assertThat(metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)).isEqualTo("Album 1")
            val cover = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)!!
            assertThat(cover.getPixel(cover.width / 2, cover.height / 2)).isEqualTo(FRONT_COLOR)
        } finally {
            onMain { browser.disconnect() }
        }
    }

    private companion object {
        /** The color of the fixtures' front cover (scripts/generate_playback_fixtures.py). */
        const val FRONT_COLOR = 0xFF3D5AFE.toInt()
    }
}
