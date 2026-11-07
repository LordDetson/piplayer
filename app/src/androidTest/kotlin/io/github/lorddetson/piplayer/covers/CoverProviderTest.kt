package io.github.lorddetson.piplayer.covers

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.browse.MediaBrowser as PlatformBrowser
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.LibraryRule
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.playback.PlaybackService
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.PlaybackStateStore
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectBrowser
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.player.TestImages
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Covers for controllers outside PiPlayer (docs/library.md §13.8): the URIs of the tree, opened through the content
 * resolver as a controller opens them. The red album has an own cover; the fake index's files have no pictures.
 */
@RunWith(AndroidJUnit4::class)
class CoverProviderTest {

    private val context = TestFiles.targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val authority = CoverUris.authorityOf(context.packageName)

    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Alpha", path = "Music/Ann/Red/", name = "01 Alpha.mp3", artist = "Ann", album = "Red Album", trackNumber = 1),
            row(2, "Bravo", path = "Music/Ann/Red/", name = "02 Bravo.mp3", artist = "Ann", album = "Red Album", trackNumber = 2),
            row(3, "Charlie", path = "Music/Bob/Plain/", name = "01 Charlie.mp3", artist = "Bob", album = "Plain", trackNumber = 1),
        )
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        runBlocking { Library.sync(context).awaitIdle() }
        runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(RED)) }
        browser = connectBrowser()
    }

    @After
    fun disconnect() {
        browser.stopAndRelease()
        runBlocking { PlaybackStateStore.get(context).clear() }
    }

    private fun fileId(mediaStoreId: Long) = runBlocking {
        Library.database(context).library().files().single { it.mediaStoreId == mediaStoreId }.id
    }

    private fun albumKey(mediaStoreId: Long) = Library.read(context) { LibraryLists(it).links(fileId(mediaStoreId)) }!!.albumKey!!

    /** A copy of an image of one [color], as a choice from the photo picker gives it; larger than what controllers get. */
    private fun copy(color: Int = RED, noise: Boolean = false) = run {
        val file = File(context.cacheDir, "chosen-${System.nanoTime()}.png")
        val bitmap = TestImages.bitmap(1_000, color = color)
        if (noise) {
            // Noise does not compress: its JPEG at 512 px is far larger than a pipe's buffer.
            val random = java.util.Random(7)
            val pixels = IntArray(1_000 * 1_000) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
            bitmap.setPixels(pixels, 0, 1_000, 0, 0, 1_000, 1_000)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        checkNotNull(CoverImport.copy(context, Uri.fromFile(file), Covers.own(context).dir))
    }

    private fun children(id: String): List<MediaItem> {
        val result = onMain { browser.getChildren(id, 0, Int.MAX_VALUE, null) }.get(30, TimeUnit.SECONDS)
        assertThat(result.resultCode).describedAs("children of $id").isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value!!
    }

    private fun titled(items: List<MediaItem>, title: String) = items.single { it.mediaMetadata.title.toString() == title }

    private fun album(title: String) = titled(children("albums"), title)

    private fun artist(name: String) = titled(children("artists"), name)

    private fun song(title: String) = titled(children("songs"), title)

    private fun uriOf(item: MediaItem): Uri = checkNotNull(item.mediaMetadata.artworkUri) { "No artwork URI on ${item.mediaId}" }

    /** What a controller reads from [uri]: all bytes, or what failed. */
    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Not an image: ${bytes.size} bytes" }

    private fun near(color: Int, expected: Int) =
        listOf(16, 8, 0).all { shift -> kotlin.math.abs((color shr shift and 0xFF) - (expected shr shift and 0xFF)) <= 12 }

    private fun assertCover(uri: Uri, color: Int) {
        val bytes = read(uri)
        // A JPEG.
        assertThat(bytes.take(2)).describedAs("$uri").containsExactly(0xFF.toByte(), 0xD8.toByte())
        val bitmap = decode(bytes)
        assertThat(bitmap.width to bitmap.height).describedAs("$uri").isEqualTo(CoverProvider.PX to CoverProvider.PX)
        assertThat(near(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), color)).describedAs("$uri").isTrue()
    }

    @Test
    fun theUrisOfTheTreeServeTheAlbumsCoverToItsSongsItselfAndItsArtist() {
        val uris = listOf(uriOf(album("Red Album")), uriOf(song("Alpha")), uriOf(song("Bravo")), uriOf(artist("Ann")))

        assertThat(uris).allSatisfy {
            assertThat(it.scheme).isEqualTo("content")
            assertThat(it.authority).isEqualTo(authority)
            // Nothing of the library's names or paths.
            assertThat(it.toString()).doesNotContain("Red", "Ann", "Music", "Alpha")
        }
        for (uri in uris) assertCover(uri, RED)
    }

    @Test
    fun aCoverWithoutAPictureFailsToOpen() {
        val uri = uriOf(song("Charlie"))

        val bytes = try {
            read(uri)
        } catch (_: IOException) {
            ByteArray(0)
        }
        assertThat(bytes).isEmpty()
        assertThat(resolver.getType(uri)).isEqualTo("image/jpeg")
    }

    @Test
    fun theTreesItemsCarryCoverUrisAndNoArtworkData() {
        val albums = children("albums")
        val artists = children("artists")
        val songs = children("songs")
        for (item in albums + artists + songs) {
            assertThat(item.mediaMetadata.artworkData).describedAs(item.mediaId).isNull()
            assertThat(item.mediaMetadata.artworkUri?.authority).describedAs(item.mediaId).isEqualTo(authority)
        }
        // The tabs and folders carry none.
        for (item in children(PlaybackSessionCallback.ROOT_ID) + children("folders")) {
            assertThat(item.mediaMetadata.artworkUri).describedAs(item.mediaId).isNull()
        }

        // A browser of the platform's protocol, as Android Auto uses: icon URIs, never bitmaps.
        val connected = CountDownLatch(1)
        val platform = onMain {
            PlatformBrowser(context, ComponentName(context, PlaybackService::class.java), object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, null).apply { connect() }
        }
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
            for (node in listOf("albums", "artists", "songs")) {
                val loaded = CountDownLatch(1)
                var legacy: List<PlatformBrowser.MediaItem> = emptyList()
                val callback = object : PlatformBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<PlatformBrowser.MediaItem>) {
                        legacy = children
                        loaded.countDown()
                    }
                }
                onMain { platform.subscribe(node, callback) }
                assertThat(loaded.await(30, TimeUnit.SECONDS)).describedAs("loaded $node").isTrue()
                onMain { platform.unsubscribe(node, callback) }
                // What each URI names; their generation may differ between the two reads.
                assertThat(legacy.map { it.description.iconUri?.let(::named) }).describedAs(node)
                    .isEqualTo(children(node).map { named(uriOf(it)) })
                assertThat(legacy).describedAs(node).allSatisfy { assertThat(it.description.iconBitmap).isNull() }
            }
        } finally {
            onMain { platform.disconnect() }
        }
    }

    @Test
    fun aNewOwnCoverGivesANewUriThatServesTheNewPicture() {
        val before = uriOf(album("Red Album"))
        val songBefore = uriOf(song("Alpha"))
        assertCover(before, RED)

        runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(TestImages.BLUE)) }

        val after = uriOf(album("Red Album"))
        assertThat(after).isNotEqualTo(before)
        assertThat(uriOf(song("Alpha"))).isNotEqualTo(songBefore)
        assertCover(after, TestImages.BLUE)
        assertCover(uriOf(song("Alpha")), TestImages.BLUE)
    }

    @Test
    fun theSameCoverKeepsItsUri() {
        assertThat(uriOf(album("Red Album"))).isEqualTo(uriOf(album("Red Album")))
        assertThat(uriOf(song("Alpha"))).isEqualTo(uriOf(song("Alpha")))
    }

    private val provider: CoverProvider
        get() = resolver.acquireContentProviderClient(authority)!!.use { it.localContentProvider as CoverProvider }

    /** The uid of an app that may not read music; see PlaybackSessionCallbackTest. */
    private val foreignUid = (Process.myUid() + 1..Process.LAST_APPLICATION_UID).first {
        context.checkPermission(AudioPermission.name, -1, it) != PackageManager.PERMISSION_GRANTED
    }

    @Test
    fun aCallerThatMayNotReadMusicIsRefusedAlsoUnderPiPlayersName() {
        val uri = uriOf(album("Red Album"))

        for (name in listOf("com.example.remote", context.packageName, null)) {
            assertThatThrownBy { provider.open(uri, "r", CoverAccess.Caller(foreignUid, 0, name)) }
                .describedAs("$name")
                .isInstanceOf(SecurityException::class.java)
        }
        assertThat(CoverAccess.allows(context, CoverAccess.Caller(foreignUid, 0, "com.example.remote"))).isFalse()
        assertThat(CoverAccess.allows(context, CoverAccess.Caller(Process.myUid(), Process.myPid(), context.packageName))).isTrue()
    }

    @Test
    fun anotherUidReadsAsTheRuleDecidesForIt() {
        val uri = uriOf(album("Red Album"))
        val own = read(uri)
        // The shell may read music on some versions, and holds the media control permission on others.
        val allowed = context.checkPermission(AudioPermission.name, -1, Process.SHELL_UID) == PackageManager.PERMISSION_GRANTED ||
            context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, -1, Process.SHELL_UID) == PackageManager.PERMISSION_GRANTED
        assertThat(CoverAccess.allows(context, CoverAccess.Caller(Process.SHELL_UID, 0, "com.android.shell"))).isEqualTo(allowed)

        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("content read --uri $uri")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(shell).use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        }

        if (allowed) assertThat(bytes).isEqualTo(own) else assertThat(bytes).isEmpty()
    }

    @Test
    fun onlyReadingIsAllowed() {
        val uri = uriOf(album("Red Album"))

        for (mode in listOf("w", "rw", "wt", "wa", "rwt")) {
            assertThatThrownBy { resolver.openFileDescriptor(uri, mode)?.close() }.describedAs(mode).isInstanceOf(SecurityException::class.java)
        }
    }

    @Test
    fun malformedUrisAreNotFoundAndHaveNoType() {
        val bad = Uri.parse("content://$authority/s/notanumber/k3x90")

        assertThatThrownBy { read(bad) }.isInstanceOf(java.io.FileNotFoundException::class.java)
        assertThat(resolver.getType(bad)).isNull()
    }

    @Test
    fun thePlayingSongCarriesItsCoverUriThroughTheService() {
        val alpha = song("Alpha")
        onMain {
            browser.setMediaItem(alpha)
            browser.prepare()
        }

        browser.waitUntil("the playing song carries its cover URI") { mediaMetadata.artworkUri != null }
        // The fake index's files do not play, so the player may have skipped on: the URI is the current item's.
        val (mediaId, uri) = onMain { browser.currentMediaItem!!.mediaId to browser.mediaMetadata.artworkUri!! }
        val fileId = checkNotNull(QueueIds.fileId(mediaId)) { "Not a library song: $mediaId" }
        assertThat(named(uri)).isEqualTo(CoverRequest.Track(fileId))

        // A new own cover: the session looks again and the browser hears a new URI for the same song.
        runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(TestImages.BLUE)) }

        browser.waitUntil("the playing song carries a new cover URI") { mediaMetadata.artworkUri != uri }
        val (nowId, now) = onMain { browser.currentMediaItem!!.mediaId to browser.mediaMetadata.artworkUri!! }
        assertThat(named(now)).isEqualTo(CoverRequest.Track(QueueIds.fileId(nowId)!!))
    }

    @Test
    fun searchResultsCarryCoverUris() {
        onMain { browser.search("Alpha", null) }.get(30, TimeUnit.SECONDS)
        val found = onMain { browser.getSearchResult("Alpha", 0, Int.MAX_VALUE, null) }.get(30, TimeUnit.SECONDS).value!!

        assertThat(found).isNotEmpty()
        assertThat(found).allSatisfy {
            assertThat(it.mediaMetadata.artworkData).isNull()
            assertThat(named(uriOf(it))).isNotNull()
        }
    }

    @Test
    fun aSearchAgainAfterANewOwnCoverGivesTheNewUri() {
        fun search(): List<MediaItem> {
            onMain { browser.search("Alpha", null) }.get(30, TimeUnit.SECONDS)
            return onMain { browser.getSearchResult("Alpha", 0, Int.MAX_VALUE, null) }.get(30, TimeUnit.SECONDS).value!!
        }
        val before = search().map(::uriOf)

        runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(TestImages.BLUE)) }
        val after = search().map(::uriOf)

        assertThat(after.map(::named)).isEqualTo(before.map(::named))
        assertThat(after.zip(before)).allSatisfy { (now, old) -> assertThat(now).isNotEqualTo(old) }
        assertCover(after.single { named(it) is CoverRequest.Track }, TestImages.BLUE)
    }

    @Test
    fun aCallerOverItsShareIsRefusedWhileAnotherIsServed() {
        // A cover larger than the pipe holds: an open whose reader takes nothing keeps its place while the write waits.
        runBlocking { Covers.own(context).setAlbum(albumKey(1), copy(noise = true)) }
        val uri = uriOf(album("Red Album"))
        val own = CoverAccess.Caller(Process.myUid(), Process.myPid(), context.packageName)
        val held = (1..CoverProvider.MAX_PER_CALLER).map { provider.open(uri, "r", own) }
        try {
            assertThatThrownBy { provider.open(uri, "r", own) }.isInstanceOf(java.io.FileNotFoundException::class.java)
            // Another caller that may read the library is served all the same.
            val shell = CoverAccess.Caller(Process.SHELL_UID, 0, null)
            if (CoverAccess.allows(context, shell)) {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(provider.open(uri, "r", shell)).use { it.readBytes() }
                assertThat(decode(bytes).width).isEqualTo(CoverProvider.PX)
            }
        } finally {
            held.forEach { it.close() }
        }
        // Readers gone, their places come back.
        eventually("the places are given back") {
            try {
                provider.open(uri, "r", own).close()
                true
            } catch (_: java.io.FileNotFoundException) {
                false
            }
        }
    }

    /** What a cover URI names, whatever its generation. */
    private fun named(uri: Uri) = Covers.uris(context).parse(uri.authority, uri.pathSegments)

    private companion object {
        const val RED = 0xFFE53935.toInt()
    }
}
