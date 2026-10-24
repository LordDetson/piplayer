package io.github.lorddetson.piplayer.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.AudioJournal.Kind
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.fileItem
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import java.io.File
import kotlin.time.Duration.Companion.seconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debug build's audio journal, and an album played through the service with its default settings
 * (docs/playback.md §8.3): automatic song changes, no pause or stall, one audio output.
 */
@RunWith(AndroidJUnit4::class)
class AudioJournalTest {

    companion object {
        /** Plays [album] through the service from the start to its end and checks the journal's account of it. */
        fun playsThrough(controller: MediaController, album: GaplessAlbum) {
            val items = album.files.map(::fileItem)
            AudioJournal.reset()
            onMain {
                controller.setMediaItems(items)
                controller.prepare()
                controller.play()
            }
            controller.waitUntil("$album ends", timeout = 40.seconds) { playbackState == Player.STATE_ENDED }

            val events = AudioJournal.events
            val started = events.indexOfFirst { it.kind == Kind.READY }
            val ended = events.indexOfFirst { it.kind == Kind.ENDED }
            assertThat(started).describedAs("ready in $events").isGreaterThanOrEqualTo(0)
            assertThat(ended).describedAs("ended in $events").isGreaterThan(started)
            val playing = events.subList(started, ended)
            assertThat(playing.filter { it.kind == Kind.PAUSE || it.kind == Kind.BUFFERING }).isEmpty()
            assertThat(playing.filter { it.kind == Kind.SONG }.map { "${it.song}: ${it.detail}" })
                .containsExactlyElementsOf((2..album.files.size).map { "$it: automatic" })
            // The previous test's stop released its output, so the album makes exactly one.
            assertThat(events.filter { it.kind == Kind.OUTPUT }).describedAs("audio outputs in $events").hasSize(1)
            assertThat(events.filter { it.kind == Kind.DECODER }).isNotEmpty()
            if (!GaplessTest.onEmulator) assertThat(events.filter { it.kind == Kind.UNDERRUN }).isEmpty()
        }
    }

    private lateinit var controller: MediaController

    @Before
    fun connect() {
        controller = connectController()
    }

    @After
    fun disconnect() {
        controller.stopAndRelease()
    }

    @Test
    fun anAlbumPlaysThroughTheServiceWithoutAPauseOnOneOutput() {
        val album = GaplessAlbum.all().single { it.name == "flac" }
        // Only this album's lines may count, and too few to start the file again.
        val journal = AudioJournal.file(TestFiles.targetContext)
        journal.delete()
        File(journal.path + ".old").delete()

        playsThrough(controller, album)

        eventually("the journal names the last song") { journal.isFile && "song 5 of 5, automatic" in journal.readText() }
        assertThat(journal.readText()).contains("decoder c2.", "output 44100 Hz, 2 channels", "ready", "ended")
    }
}
