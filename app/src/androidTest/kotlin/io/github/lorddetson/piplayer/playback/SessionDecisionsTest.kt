package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.browse.MediaBrowser
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.Covers
import io.github.lorddetson.piplayer.covers.SavedCovers
import io.github.lorddetson.piplayer.identification.caa.CoverEntity
import io.github.lorddetson.piplayer.identification.caa.CoverSource
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.library.songs.SongList
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.tap
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.userdata.DecisionFixtures
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * What the controllers and the platform session show after Apply and Undo (TI §7.1, §11.2 M3 "Session metadata";
 * docs/playback.md §2.6): the playback service over the app's library, filled from a test index whose files are real
 * files of the media index, so that they play and the cover loader reads their embedded pictures.
 *
 * The fixture is an MP3 without a frame-count header (the 12 s CBR seek fixture, ten times over, so about two minutes:
 * long enough that X still plays when a slow emulator has waited its way through every step) behind an ID3v2 tag with a
 * title, artist and album and a front cover: its tags and its embedded cover differ from the library's values
 * (the test index's) and from the decided ones. The queue is X, Y and X2, where X2 is another file with X's content, so
 * of the same track.
 */
@RunWith(AndroidJUnit4::class)
class SessionDecisionsTest {

    private val context = TestFiles.targetContext
    private val resolver = context.contentResolver
    private val inserted = mutableListOf<Uri>()
    private val index = FakeMediaIndex()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var controller: MediaController
    private var platformBrowser: MediaBrowser? = null
    private val ids = LongArray(3)

    private fun key(n: Long) = "%064x".format(n)

    @Before
    fun prepare() = runBlocking {
        deleteLeftovers()
        val fixture = fixture()
        val stores = NAMES.map { insert(it, fixture) }
        index.volume().rows = listOf(
            row(stores[0], "Alpha", path = FOLDER, name = NAMES[0], artist = "Ann", album = "First", durationMs = DURATION_MS),
            row(stores[1], "Bravo", path = FOLDER, name = NAMES[1], artist = "Bob", album = "Second", durationMs = DURATION_MS),
            row(stores[2], "Charlie", path = FOLDER, name = NAMES[2], artist = "Cid", album = "Third", durationMs = DURATION_MS),
        )
        Library.sync(context).sync()
        Library.sync(context).awaitIdle()
        val dao = Library.database(context).library()
        stores.forEachIndexed { i, store -> ids[i] = dao.files().single { it.mediaStoreId == store }.id }
        // X and X2 hold the same content, so one track; Y another.
        listOf(key(1), key(2), key(1)).forEachIndexed { i, key -> dao.setContent(ids[i], key, DURATION_MS, DurationSource.WALK) }
        Library.identities(context).attach()
        Library.decisionMirror(context).awaitIdle()
        controller = connectController()
        onMain {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
        }
    }

    @After
    fun release() {
        if (::controller.isInitialized) controller.stopAndRelease()
        platformBrowser?.let { browser -> onMain { browser.disconnect() } }
        inserted.forEach { runCatching { resolver.delete(it, null, null) } }
    }

    /** The song the library gives for [fileId] now: PiPlayer's values. */
    private fun libraryValues(fileId: Long): Song = checkNotNull(SongList.songsById(context, listOf(fileId))[fileId]) { "No song $fileId" }

    /** What controllers hear of the queue and the playback, from the moment playback is under way. */
    private class Heard : Player.Listener {
        val states = CopyOnWriteArrayList<Int>()
        val transitions = CopyOnWriteArrayList<Int>()
        val discontinuities = CopyOnWriteArrayList<Int>()

        override fun onPlaybackStateChanged(playbackState: Int) {
            states += playbackState
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transitions += reason
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            discontinuities += reason
        }

        override fun toString() = "states $states, transitions $transitions, discontinuities $discontinuities"
    }

    @Test
    fun applyAndUndoReachTheControllersWithoutANewPreparationAndLeaveTheNextItemOfAnotherTrack() = runBlocking<Unit> {
        controller.tap(QueueRequest(startSongId = ids[0], startRow = 0))
        controller.waitUntil("X plays at the head of the queue", 20.seconds) {
            isPlaying && mediaItemCount == 3 && currentMediaItemIndex == 0
        }
        val started = TimeSource.Monotonic.markNow()
        assertThat(onMain { List(3) { controller.getMediaItemAt(it).mediaId } }).containsExactly(*ids.map { it.toString() }.toTypedArray())
        val platform = platformController()
        // Before any decision: the library's values and the embedded picture as the resolver reads it, not the stream's tags.
        awaitShown("the library's values before Apply", ids[0], FRONT_COLOR, platform)
        val heard = Heard()
        val before = onMain {
            controller.addListener(heard)
            controller.currentPosition
        }

        // Y's library values change meanwhile; nothing tells the session, so its item stays as it was.
        index.volume().rows = index.volume().rows.map { if (it.title == "Bravo") it.copy(title = "Bravo again", generationModified = it.generationModified + 1_000) else it }
        Library.sync(context).sync()
        assertThat(libraryValues(ids[1]).title).isEqualTo("Bravo again")

        val cover = checkNotNull(Covers.saved(context).save(jpeg(Color.RED), DecisionFixtures.mbid(1, 0x1e1), SavedCovers.Source.RELEASE))
        val applied = checkNotNull(
            Library.decisions(context).apply(
                DecisionFixtures.request(
                    key(1),
                    DecisionFixtures.details(title = "Decided title", artist = "Decided artist", album = "Decided album"),
                    fileId = ids[0],
                ).copy(fields = ProtectedField.entries.toSet(), cover = CoverSource(CoverEntity.RELEASE, DecisionFixtures.mbid(1, 0x1e1))),
                savedCoverId = cover,
            ),
        )

        assertThat(libraryValues(ids[0]).title).isEqualTo("Decided title")
        awaitShown("the decided values after Apply", ids[0], Color.RED, platform)
        controller.waitUntil("X2, of the same track, follows") { getMediaItemAt(2).mediaMetadata.title.toString() == "Decided title" }
        onMain {
            assertThat(controller.getMediaItemAt(2).mediaMetadata.artist.toString()).isEqualTo("Decided artist")
            assertThat(controller.getMediaItemAt(1).mediaMetadata.title.toString()).describedAs("the next item, of another track").isEqualTo("Bravo")
        }

        checkNotNull(Library.decisions(context).undo(applied.decisionId))

        assertThat(libraryValues(ids[0]).title).isEqualTo("Alpha")
        awaitShown("the library's values after Undo", ids[0], FRONT_COLOR, platform)
        controller.waitUntil("X2 shows its own values again") { getMediaItemAt(2).mediaMetadata.title.toString() == "Charlie" }
        assertThat(started.elapsedNow().inWholeMilliseconds).describedAs("the test's steps took less time than X plays, with room to spare")
            .isLessThan(DURATION_MS - ROOM_MS)
        onMain {
            assertThat(controller.getMediaItemAt(1).mediaMetadata.title.toString()).describedAs("the next item, of another track").isEqualTo("Bravo")
            assertThat(heard.states).describedAs("no new preparation: $heard").isEmpty()
            // A controller tells a current item with new metadata as a change of the playlist, never as a move to another
            // item; the player itself reports none (SessionMetadataTest).
            assertThat(heard.transitions).describedAs("no move to another item: $heard")
                .allMatch { it == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED }
            assertThat(heard.discontinuities).describedAs("no jump: $heard").isEmpty()
            assertThat(controller.isPlaying).isTrue()
            assertThat(controller.currentMediaItemIndex).isEqualTo(0)
            assertThat(controller.currentPosition).isGreaterThan(before)
        }
    }

    /**
     * Waits until the controller and the platform session show PiPlayer's values for [fileId], with a cover of [color],
     * and checks the durations: the metadata's is the stored exact one; the timeline's is the player's own, and the
     * platform session gives the same one as the controller (TI §7.1), close to the stored one for this constant-bitrate
     * file.
     */
    private fun awaitShown(what: String, fileId: Long, color: Int, platform: android.media.session.MediaController) {
        val song = libraryValues(fileId)
        controller.waitUntil("$what: the controller") {
            mediaMetadata.title?.toString() == song.title && mediaMetadata.artist?.toString() == song.artist &&
                mediaMetadata.albumTitle?.toString() == song.album && near(cover(mediaMetadata.artworkData), color)
        }
        onMain {
            assertThat(controller.mediaMetadata.durationMs).isEqualTo(song.durationMs).isEqualTo(DURATION_MS)
            assertThat(controller.currentMediaItem!!.mediaMetadata.durationMs).isEqualTo(DURATION_MS)
            assertThat(controller.currentMediaItem!!.mediaMetadata.title.toString()).isEqualTo(song.title)
            assertThat(controller.mediaMetadata.artworkUri).describedAs("the cover URI").isNotNull()
        }
        val timeline = onMain { controller.duration }
        assertThat(timeline).describedAs("the timeline's length, the player's own").isCloseTo(DURATION_MS, within(ESTIMATE_MS))
        eventually("$what: the platform session, which Bluetooth and watches read") {
            val metadata = onMain { platform.metadata }
            metadata != null && metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == song.title &&
                metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) == song.artist &&
                metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) == song.album &&
                near(metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)?.let(::center), color)
        }
        assertThat(onMain { platform.metadata!!.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) })
            .describedAs("the platform session's length, the controller's").isEqualTo(timeline)
    }

    private fun platformController(): android.media.session.MediaController {
        val connected = CountDownLatch(1)
        val browser = onMain {
            MediaBrowser(
                context,
                ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null,
            ).also { it.connect() }
        }
        platformBrowser = browser
        assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
        return onMain { android.media.session.MediaController(context, browser.sessionToken) }
    }

    private fun center(bitmap: Bitmap) = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

    /** The color at the middle of the picture [bytes], or null without one. */
    private fun cover(bytes: ByteArray?): Int? = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let(::center)

    /** JPEG encodes each color a little off. */
    private fun near(color: Int?, expected: Int): Boolean = color != null &&
        listOf(Color::red, Color::green, Color::blue).all { channel -> kotlin.math.abs(channel(color) - channel(expected)) <= 12 }

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 95, it)
    }.toByteArray()

    /**
     * The fixture: an ID3v2.3 tag with the title, artist and album frames of the tagged fixture and the front cover of the
     * cover fixture, then the audio of the CBR seek fixture, which has no frame-count header, [REPEATS] times.
     */
    private fun fixture(): ByteArray {
        val frames = id3Frames("playback/tagged-44100-stereo.mp3") + id3Frames("playback/cover-front.mp3")
        val size = frames.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte(),
        )
        val audio = TestFiles.assets.open(PlaybackTesting.LONG_FIXTURE).use { it.readBytes() }
        return (0 until REPEATS).fold(header + frames) { bytes, _ -> bytes + audio }
    }

    /** The frames of the ID3v2.3 tag at the start of [asset], without its header and padding. */
    private fun id3Frames(asset: String): ByteArray {
        val bytes = TestFiles.assets.open(asset).use { it.readBytes() }
        check(bytes[0] == 'I'.code.toByte() && bytes[3] == 3.toByte()) { "$asset starts with no ID3v2.3 tag" }
        val end = 10 + ((bytes[6].toInt() shl 21) or (bytes[7].toInt() shl 14) or (bytes[8].toInt() shl 7) or bytes[9].toInt())
        var at = 10
        while (at + 10 <= end && bytes[at] != 0.toByte()) {
            val length = ((bytes[at + 4].toInt() and 0xff) shl 24) or ((bytes[at + 5].toInt() and 0xff) shl 16) or
                ((bytes[at + 6].toInt() and 0xff) shl 8) or (bytes[at + 7].toInt() and 0xff)
            at += 10 + length
        }
        return bytes.copyOfRange(10, at)
    }

    /** Puts [bytes] into the media index as the app's own file [name] (no permission needed), and gives its ID there. */
    private fun insert(name: String, bytes: ByteArray): Long {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(FakeMediaIndex.PRIMARY), values)) { "Could not insert $name" }
        inserted += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return ContentUris.parseId(uri)
    }

    /** Files of an earlier run that died before its clean-up; the app may delete its own files. */
    private fun deleteLeftovers() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(FOLDER, context.packageName),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
            }
        }
    }

    private companion object {
        const val FOLDER = "Music/PiPlayer session test/"
        val NAMES = listOf("x.mp3", "y.mp3", "x2.mp3")

        /** How many times the fixture holds the seek fixture's audio. */
        const val REPEATS = 10

        /** The stored exact length of the fixture as the test's library holds it. */
        const val DURATION_MS = REPEATS * SongSourceRule.LONG_FIXTURE_MS

        /** The part of X's length the test's steps must leave unplayed, so that a slow run fails here, clearly. */
        const val ROOM_MS = 15_000L

        /** How far the player's own length, from the first frame's bitrate and the file's size, may be from the stored one. */
        const val ESTIMATE_MS = 500L

        /** The color of the fixtures' front cover (scripts/generate_playback_fixtures.py). */
        const val FRONT_COLOR = 0xFF3D5AFE.toInt()
    }
}
