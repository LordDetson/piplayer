package io.github.lorddetson.piplayer.playback

import android.content.ComponentName
import android.media.browse.MediaBrowser as PlatformBrowser
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
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
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectBrowser
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.waitUntil
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The library tree as a browser of PiPlayer's own process sees it through the playback service (docs/playback.md §10),
 * on a library database filled from a test index.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTreeTest {

    private val context = TestFiles.targetContext
    private val store = PlaybackStateStore.get(context)

    /**
     * Ann's album First, Bob's album Second on two discs, a song of Ann without an album right in Music, and 600 songs of
     * Longer on the album Long in `Music/Long/`, titled from A to Z in turn: more than one node holds.
     */
    private val index = FakeMediaIndex().apply {
        volume().rows = listOf(
            row(1, "Bravo", path = "Music/Ann/First/", name = "02 Bravo.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 2),
            row(2, "Alpha", path = "Music/Ann/First/", name = "01 Alpha.mp3", artist = "Ann", album = "First", year = 1990, trackNumber = 1),
            row(3, "Charlie", path = "Music/Bob/Second/CD1/", name = "01 Charlie.mp3", artist = "Bob", album = "Second", albumArtist = "Bob", year = 2001, trackNumber = 1001),
            row(4, "Delta", path = "Music/Bob/Second/CD2/", name = "01 Delta.mp3", artist = "Bob", album = "Second", albumArtist = "Bob", year = 2001, trackNumber = 2001),
            row(5, "Golf", path = "Music/", name = "golf.mp3", artist = "Ann", album = null, year = null),
        ) + (0 until LONG).map { i ->
            row(
                100L + i, "${'A' + i % 26}ong ${"%03d".format(i)}", path = "Music/Long/", name = "%03d.mp3".format(i),
                artist = "Longer", album = "Long", year = 2000, trackNumber = i + 1,
            )
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(LibraryRule { index })

    private lateinit var browser: MediaBrowser

    @Before
    fun connect() {
        runBlocking { Library.sync(context).awaitIdle() }
        browser = connectBrowser()
        runBlocking { store.clear() }
    }

    @After
    fun disconnect() {
        browser.stopAndRelease()
        runBlocking { store.clear() }
    }

    private fun children(id: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<MediaItem> {
        val result = onMain { browser.getChildren(id, page, pageSize, null) }.get(30, TimeUnit.SECONDS)
        assertThat(result.resultCode).describedAs("children of $id").isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value!!
    }

    private fun item(id: String): MediaItem {
        val result = onMain { browser.getItem(id) }.get(30, TimeUnit.SECONDS)
        assertThat(result.resultCode).describedAs("item $id").isEqualTo(LibraryResult.RESULT_SUCCESS)
        return result.value!!
    }

    private val titles: (List<MediaItem>) -> List<String> = { items -> items.map { it.mediaMetadata.title.toString() } }

    /** The album node of [title], found among the Albums node's children. */
    private fun album(title: String) = children("albums").single { it.mediaMetadata.title.toString() == title }

    @Test
    fun theRootHasTheFourTabsAsBrowsableNodes() {
        val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS).value!!
        assertThat(root.mediaId).isEqualTo(PlaybackSessionCallback.ROOT_ID)
        assertThat(root.mediaMetadata.isBrowsable).isTrue()
        assertThat(root.mediaMetadata.isPlayable).isFalse()

        val nodes = children(root.mediaId)

        assertThat(nodes.map { it.mediaId }).containsExactly("songs", "albums", "artists", "folders")
        assertThat(titles(nodes)).containsExactly("Songs", "Albums", "Artists", "Folders")
        assertThat(nodes).allSatisfy { assertThat(it.mediaMetadata.isBrowsable).isTrue() }
        assertThat(nodes.map { it.mediaMetadata.isPlayable }).containsExactly(true, false, false, false)
    }

    @Test
    fun songsLongerThanTheLimitSplitIntoLetterBuckets() {
        val buckets = children("songs")

        assertThat(titles(buckets)).containsExactlyElementsOf(('A'..'Z').map { it.toString() })
        assertThat(buckets.first().mediaId).isEqualTo("songs!A")
        assertThat(buckets).allSatisfy {
            assertThat(it.mediaMetadata.isBrowsable).isTrue()
            assertThat(it.mediaMetadata.isPlayable).isTrue()
        }
        val songs = buckets.flatMap { children(it.mediaId) }
        assertThat(songs).hasSize(LONG + 5)

        val b = children("songs!B")
        assertThat(titles(b).first()).isEqualTo("Bong 001")
        assertThat(titles(b)).contains("Bravo")
        // A song names the Songs list in the tab's order, starting at it, with its row as the fallback.
        val request = QueueRequest.parse(b.first().mediaId)!!
        assertThat(request.list).isEqualTo(SongQuery(ListKind.SONGS, sort = SongSort.TITLE))
        assertThat(request.startRow).isEqualTo(songs.indexOfFirst { it.mediaId == b.first().mediaId })
        assertThat(b.first().mediaMetadata.isPlayable).isTrue()
        assertThat(b.first().mediaMetadata.isBrowsable).isFalse()
        assertThat(b.first().mediaMetadata.artist.toString()).isEqualTo("Longer")
        assertThat(b.first().mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    }

    @Test
    fun everyNodeHasAtMostTheLimitNoArtworkDataAndEveryIdResolves() {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(PlaybackSessionCallback.ROOT_ID))
        var nodes = 0
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val items = children(id)
            nodes++
            assertThat(items.size).describedAs("children of $id").isBetween(1, TreeBuckets.MAX_CHILDREN)
            items.forEachIndexed { i, child ->
                assertThat(child.mediaMetadata.artworkData).describedAs(child.mediaId).isNull()
                // Every node, and the first and last song of each node.
                if (child.mediaMetadata.isBrowsable == true || i == 0 || i == items.lastIndex) {
                    val resolved = item(child.mediaId)
                    assertThat(resolved.mediaId).isEqualTo(child.mediaId)
                    assertThat(resolved.mediaMetadata.title).isEqualTo(child.mediaMetadata.title)
                    assertThat(resolved.mediaMetadata.isBrowsable).isEqualTo(child.mediaMetadata.isBrowsable)
                    assertThat(resolved.mediaMetadata.isPlayable).isEqualTo(child.mediaMetadata.isPlayable)
                    assertThat(resolved.mediaMetadata.artworkData).isNull()
                }
                if (child.mediaMetadata.isBrowsable == true && seen.add(child.mediaId)) queue.addLast(child.mediaId)
            }
        }
        assertThat(nodes).isGreaterThan(40)
        // An ID that names nothing is an error.
        assertThat(onMain { browser.getChildren("album/nothing", 0, 10, null) }.get(10, TimeUnit.SECONDS).resultCode)
            .isEqualTo(LibraryResult.RESULT_ERROR_BAD_VALUE)
        assertThat(onMain { browser.getItem("songs!Q!9") }.get(10, TimeUnit.SECONDS).resultCode)
            .isEqualTo(LibraryResult.RESULT_ERROR_BAD_VALUE)
    }

    @Test
    fun anAlbumListsItsSongsByDiscAndTrackAndALongOneSplitsIntoRunsOfRows() {
        val first = album("First")
        assertThat(first.mediaId).startsWith("album/")
        assertThat(first.mediaMetadata.artist.toString()).isEqualTo("Ann")
        assertThat(first.mediaMetadata.releaseYear).isEqualTo(1990)
        assertThat(first.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_ALBUM)
        assertThat(first.mediaMetadata.isPlayable).isTrue()

        assertThat(titles(children(first.mediaId))).containsExactly("Alpha", "Bravo")
        assertThat(titles(children(album("Second").mediaId))).containsExactly("Charlie", "Delta")
        val long = children(album("Long").mediaId)
        // Disc and track order has no sections: runs of rows, of at most 500 and as many as a legacy answer holds.
        val runs = titles(long).map { it.split('-').map(String::toInt) }
        assertThat(runs.first()[0]).isEqualTo(1)
        assertThat(runs.last()[1]).isEqualTo(LONG)
        assertThat(runs).allSatisfy { (from, to) -> assertThat(to - from + 1).isBetween(1, TreeBuckets.MAX_CHILDREN) }
        val second = runs[1][0] - 1
        assertThat(titles(children(long[1].mediaId)).first()).isEqualTo("${'A' + second % 26}ong ${"%03d".format(second)}")
    }

    @Test
    fun anArtistListsTheirAlbumsByYearThenTheirSongs() {
        val ann = children("artists").single { it.mediaMetadata.title.toString() == "Ann" }
        assertThat(ann.mediaId).isEqualTo("artist/ann")
        assertThat(ann.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_ARTIST)

        val entries = children(ann.mediaId)

        assertThat(titles(entries)).containsExactly("First", "Alpha", "Bravo", "Golf")
        assertThat(entries[0].mediaMetadata.isBrowsable).isTrue()
        assertThat(QueueRequest.parse(entries[1].mediaId)!!.list).isEqualTo(SongQuery(ListKind.ARTIST, "ann", SongSort.YEAR))
    }

    @Test
    fun theFoldersFollowTheFoldersTab() {
        val top = children("folders")
        assertThat(titles(top)).containsExactly("Music")
        assertThat(top.single().mediaId).isEqualTo(TreeNode.Folder(FolderKey(FakeMediaIndex.PRIMARY, "Music/")).mediaId)

        val music = children(top.single().mediaId)

        assertThat(titles(music)).containsExactly("Ann", "Bob", "Long", "Golf")
        assertThat(QueueRequest.parse(music.last().mediaId)!!.list)
            .isEqualTo(SongQuery(ListKind.FOLDER, FolderKey(FakeMediaIndex.PRIMARY, "Music/").key, SongSort.FILE_NAME))
        // A folder of 600 songs whose file names all start with a digit: one section, in parts.
        assertThat(titles(children(music[2].mediaId))).containsExactly("# (1)", "# (2)")
    }

    @Test
    fun childrenComeInThePagesAsked() {
        val all = children("songs!A")

        assertThat(children("songs!A", page = 1, pageSize = 5)).isEqualTo(all.subList(5, 10))
        assertThat(children("songs!A", page = 100, pageSize = 5)).isEmpty()
        assertThat(children("songs", page = 0, pageSize = 3).map { it.mediaId }).containsExactly("songs!A", "songs!B", "songs!C")
    }

    @Test
    fun aBucketOfSongsPlaysTheWholeListFromItsFirstSong() {
        val bucket = children("songs").single { it.mediaId == "songs!B" }

        onMain { browser.setMediaItem(bucket) }

        browser.waitUntil("the whole list is the queue") { mediaItemCount == LONG + 5 }
        assertThat(onMain { browser.currentMediaItem?.mediaMetadata?.title.toString() }).isEqualTo("Bong 001")
    }

    @Test
    fun searchFindsArtistsAlbumsAndSongsAsTheAppDoes() {
        val searched = onMain { browser.search("ann", null) }.get(10, TimeUnit.SECONDS)
        assertThat(searched.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)

        val found = onMain { browser.getSearchResult("ann", 0, 100, null) }.get(10, TimeUnit.SECONDS).value!!

        assertThat(titles(found)).containsExactly("Ann", "First", "Alpha", "Bravo", "Golf")
        assertThat(found[0].mediaId).isEqualTo("artist/ann")
        assertThat(found[1].mediaId).isEqualTo(album("First").mediaId)
        assertThat(QueueRequest.parse(found[2].mediaId)!!.list).isEqualTo(SongQuery(ListKind.SONGS, sort = SongSort.TITLE, search = "ann"))
        assertThat(onMain { browser.getSearchResult("ann", 1, 2, null) }.get(10, TimeUnit.SECONDS).value!!).isEqualTo(found.subList(2, 4))
        // Longer, Long and 600 songs hold "ong": the songs stop at 300.
        val many = onMain { browser.getSearchResult("ong", 0, Int.MAX_VALUE, null) }.get(10, TimeUnit.SECONDS).value!!
        assertThat(many).hasSize(1 + 1 + 300)
    }

    @Test
    fun addedTreeItemsBecomeAddedEntriesOfTheQueue() {
        val first = album("First")
        onMain { browser.setMediaItem(first) }
        browser.waitUntil("the album is the queue") { mediaItemCount == 2 }
        val golf = children("folders").let { children(it.single().mediaId) }.last()

        val second = album("Second")
        onMain { browser.addMediaItems(1, listOf(golf, second)) }

        browser.waitUntil("three songs joined") { mediaItemCount == 5 }
        val ids = onMain { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it).mediaId } }
        assertThat(ids.drop(1).take(3)).allSatisfy { assertThat(QueueIds.addedNumber(it)).isNotNull() }
        assertThat(onMain { (1..3).map { browser.getMediaItemAt(it).mediaMetadata.title.toString() } }).containsExactly("Golf", "Charlie", "Delta")
        eventually("the queue file journals the add") {
            val file = store.readQueueBlocking()
            file != null && file.added.size == 3 && file.journal.size == 1
        }
    }

    /**
     * A browser of the platform's protocol, as Android Auto and AVRCP use: it asks for whole nodes, and Media3 cuts the
     * answer silently at 256 KB (§10.2).
     */
    @Test
    fun aLegacyBrowserGetsWholeNodesWithoutBitmaps() {
        // 450 songs with long titles: fewer than the limit of entries, more than a legacy answer holds.
        index.volume().rows = index.volume().rows + (0 until WIDE).map { i ->
            row(3_000L + i, "Wide " + "x".repeat(150) + " %03d".format(i), path = "Music/Wide/", name = "w%03d.mp3".format(i), artist = "Wide", album = "Wide", generation = 10_000L + i)
        }
        runBlocking {
            Library.sync(context).request()
            Library.sync(context).awaitIdle()
        }
        val music = TreeNode.Folder(FolderKey(FakeMediaIndex.PRIMARY, "Music/")).mediaId
        val wide = children(music).single { it.mediaMetadata.title.toString() == "Wide" }.mediaId
        val wideBuckets = children(wide)
        assertThat(wideBuckets.size).describedAs("buckets of Wide").isGreaterThan(1)
        val nodes = wideBuckets.map { it.mediaId } + children(album("Long").mediaId).map { it.mediaId } + listOf("songs!A", "artists", "songs")

        val connected = CountDownLatch(1)
        val platform = onMain {
            PlatformBrowser(context, ComponentName(context, PlaybackService::class.java), object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, null).apply { connect() }
        }
        try {
            assertThat(connected.await(10, TimeUnit.SECONDS)).describedAs("connected").isTrue()
            val legacy = nodes.associateWith { id ->
                val loaded = CountDownLatch(1)
                var items: List<PlatformBrowser.MediaItem> = emptyList()
                val callback = object : PlatformBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: List<PlatformBrowser.MediaItem>) {
                        items = children
                        loaded.countDown()
                    }
                }
                onMain { platform.subscribe(id, callback) }
                assertThat(loaded.await(30, TimeUnit.SECONDS)).describedAs("loaded $id").isTrue()
                onMain { platform.unsubscribe(id, callback) }
                items
            }

            for ((id, items) in legacy) {
                assertThat(items.map { it.mediaId }).describedAs(id).isEqualTo(children(id).map { it.mediaId })
                assertThat(items).describedAs(id).allSatisfy { assertThat(it.description.iconBitmap).isNull() }
            }
            assertThat(wideBuckets.sumOf { legacy.getValue(it.mediaId).size }).isEqualTo(WIDE)
        } finally {
            onMain { platform.disconnect() }
        }
    }

    private companion object {
        const val LONG = 600
        const val WIDE = 450
    }
}