package io.github.lorddetson.piplayer.library.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.db.TrackMirrorEntity
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.PlayStats
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mirror of favorites and plays on a fake media index and the real databases (docs/library.md §15.3, §15.4). The
 * keys the tag pass would store are set directly, and identities attach by the real attach pass.
 */
@RunWith(AndroidJUnit4::class)
class UserMirrorTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private var now = 1_800_000_000_000L
    private val attacher = IdentityAttacher(library, user, sync, clock = { now })
    private val dir = File(context.cacheDir, "mirror-covers").apply { deleteRecursively(); mkdirs() }
    private val covers = OwnCovers(user, dir) { now }
    private val mirror = UserMirror(library, user, sync, covers, clock = { now })
    private val favorites = Favorites(user) { now }
    private val plays = PlayStats(user)

    @After
    fun close() {
        mirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
        dir.deleteRecursively()
    }

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    /** Syncs [rows], gives each file the key of its media ID (or [keys]) and attaches identities. */
    private suspend fun library(rows: List<IndexRow>, keys: Map<Long, Long> = emptyMap()) {
        index.volume().rows = rows
        sync.sync()
        for (row in rows) {
            val k = keys[row.mediaStoreId] ?: row.mediaStoreId
            library.library().setContent(fileId(row.mediaStoreId), key(k), 200_000, DurationSource.HEADER)
        }
        attacher.attach()
    }

    private suspend fun identityOf(n: Long) = checkNotNull(user.identities().idOfKey(Hex.bytes(key(n))))

    private suspend fun albumKeyOf(mediaStoreId: Long): String {
        val id = fileId(mediaStoreId)
        return checkNotNull(LibraryLists(library).links(id)?.albumKey)
    }

    private suspend fun tracks() = library.mirror().tracks()

    private suspend fun albums() = library.mirror().albums().map { it.albumKey }

    private fun album(id: Long, path: String, album: String = "Album") =
        row(id, path = path, album = album, artist = "Artist", albumArtist = "Artist", generation = id)

    @Test
    fun favoritesAndPlaysAreMirroredToEveryFileOfTheirKey() = runBlocking<Unit> {
        // Files 2 and 3 are byte-identical copies.
        library(listOf(row(1), row(2), row(3), row(4)), keys = mapOf(3L to 2L))
        favorites.set(identityOf(1), favorite = true)
        favorites.set(identityOf(2), favorite = true)
        plays.record(identityOf(2), startedAt = 100, listenedMs = 60_000)
        plays.record(identityOf(2), startedAt = 300, listenedMs = 60_000)
        plays.record(identityOf(4), startedAt = 200, listenedMs = 60_000)

        mirror.mirror()

        assertThat(tracks()).containsExactlyInAnyOrder(
            TrackMirrorEntity(fileId(1), favorite = true, playCount = 0, lastPlayedAt = null),
            TrackMirrorEntity(fileId(2), favorite = true, playCount = 2, lastPlayedAt = 300),
            TrackMirrorEntity(fileId(3), favorite = true, playCount = 2, lastPlayedAt = 300),
            TrackMirrorEntity(fileId(4), favorite = false, playCount = 1, lastPlayedAt = 200),
        )
    }

    @Test
    fun aFavoriteThatGoesLeavesTheMirror() = runBlocking<Unit> {
        library(listOf(row(1), row(2)))
        favorites.set(identityOf(1), favorite = true)
        mirror.mirror()
        assertThat(tracks().map { it.fileId }).containsExactly(fileId(1))

        favorites.set(identityOf(1), favorite = false)
        mirror.mirror()

        assertThat(tracks()).isEmpty()
    }

    @Test
    fun foldedPlaysCountWithTheEventsAndTheFoldChangesNothingMirrored() = runBlocking<Unit> {
        library(listOf(row(1)))
        val old = now - PlayStats.KEEP_EVENTS_MS - 1
        plays.record(identityOf(1), startedAt = old, listenedMs = 50_000)
        plays.record(identityOf(1), startedAt = now - 10, listenedMs = 70_000)

        // The first run of a process folds the old event.
        mirror.mirror()

        assertThat(user.plays().events().map { it.startedAt }).containsExactly(now - 10)
        assertThat(user.plays().stats().single().playCount).isEqualTo(1)
        assertThat(user.plays().stats().single().listenedMs).isEqualTo(50_000)
        assertThat(tracks().single()).isEqualTo(TrackMirrorEntity(fileId(1), false, playCount = 2, lastPlayedAt = now - 10))
    }

    @Test
    fun favoriteAlbumsAreMirroredAndListedByTheAlbumsFilter() = runBlocking<Unit> {
        library(listOf(album(1, "Music/A/", "A"), album(2, "Music/B/", "B")))
        val a = albumKeyOf(1)
        favorites.setAlbum(a, favorite = true)

        mirror.mirror()

        assertThat(albums()).containsExactly(a)
        assertThat(LibraryLists(library).albums(favoritesOnly = true).map { it.key }).containsExactly(a)
        assertThat(LibraryLists(library).albums().map { it.key }).hasSize(2)
    }

    @Test
    fun aPartialRunWritesTheRowsOfNewFilesFromTheLastFullRun() = runBlocking<Unit> {
        library(listOf(row(1)))
        favorites.set(identityOf(1), favorite = true)
        mirror.mirror()

        // A copy of the same audio appears and the tag pass reads it.
        index.volume().rows = listOf(row(1), row(2))
        sync.sync()
        library.library().setContent(fileId(2), key(1), 200_000, DurationSource.HEADER)
        mirror.mirrorFiles(listOf(fileId(2)))

        assertThat(tracks().map { it.fileId }).containsExactlyInAnyOrder(fileId(1), fileId(2))
    }

    @Test
    fun anAlbumWhoseFolderMovedTakesItsFavoriteAndCoverAlong() = runBlocking<Unit> {
        val before = listOf(album(1, "Music/A/"), album(2, "Music/A/"), album(3, "Music/A/"))
        library(before)
        val old = albumKeyOf(1)
        favorites.setAlbum(old, favorite = true)
        covers.setAlbum(old, CoverCopy(cover("old.jpg"), 10, 10))
        mirror.mirror()

        index.volume().rows = before.map { it.copy(relativePath = "Music/B/", generationModified = it.generationModified + 10) }
        sync.sync()
        val new = albumKeyOf(1)
        assertThat(new).isNotEqualTo(old)
        mirror.mirror()

        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(new)
        covers.load()
        assertThat(covers.album(new)).isEqualTo("old.jpg")
        assertThat(covers.album(old)).isNull()
        assertThat(albums()).containsExactly(new)
        assertThat(user.favorites().members().map { it.albumKey }.toSet()).containsExactly(new)
    }

    @Test
    fun mostOfAnAlbumMovedTakesItsFavoriteAndCoverAlongAlsoWhenTheRestStays() = runBlocking<Unit> {
        val before = listOf(album(1, "Music/A/"), album(2, "Music/A/"), album(3, "Music/A/"))
        library(before)
        val old = albumKeyOf(1)
        favorites.setAlbum(old, favorite = true)
        covers.setAlbum(old, CoverCopy(cover("old.jpg"), 10, 10))
        mirror.mirror()

        // Two of the three files move; the third still names the old album.
        index.volume().rows = before.map {
            if (it.mediaStoreId == 3L) it else it.copy(relativePath = "Music/B/", generationModified = it.generationModified + 10)
        }
        sync.sync()
        val new = albumKeyOf(1)
        assertThat(albumKeyOf(3)).isEqualTo(old)
        mirror.mirror()

        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(new)
        covers.load()
        assertThat(covers.album(new)).isEqualTo("old.jpg")
        assertThat(covers.album(old)).isNull()
        assertThat(user.favorites().members().map { it.albumKey }.toSet()).containsExactly(new)
    }

    @Test
    fun aCoverMoveThatFailsIsTriedAgainByTheNextRun() = runBlocking<Unit> {
        val before = listOf(album(1, "Music/A/"), album(2, "Music/A/"))
        library(before)
        val old = albumKeyOf(1)
        favorites.setAlbum(old, favorite = true)
        covers.setAlbum(old, CoverCopy(cover("old.jpg"), 10, 10))
        mirror.mirror()
        index.volume().rows = before.map { it.copy(relativePath = "Music/B/", generationModified = it.generationModified + 10) }
        sync.sync()
        val new = albumKeyOf(1)

        val failingOnce = object : AlbumCoverMoves by covers {
            var failed = false

            override suspend fun moveAlbumCovers(moves: Map<String, String>) {
                if (!failed) {
                    failed = true
                    throw IOException("The test's failed cover move")
                }
                covers.moveAlbumCovers(moves)
            }
        }
        val flaky = UserMirror(library, user, sync, failingOnce, clock = { now })
        try {
            assertThat(runCatching { flaky.mirror() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
            flaky.mirror()
        } finally {
            flaky.close()
        }

        covers.load()
        assertThat(covers.album(new)).isEqualTo("old.jpg")
        assertThat(covers.album(old)).isNull()
        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(new)
    }

    @Test
    fun anAlbumSplitInTwoHalvesKeepsItsReferences() = runBlocking<Unit> {
        val before = (1L..4L).map { album(it, "Music/A/") }
        library(before)
        val old = albumKeyOf(1)
        favorites.setAlbum(old, favorite = true)
        mirror.mirror()

        index.volume().rows = before.map {
            it.copy(relativePath = if (it.mediaStoreId <= 2) "Music/B/" else "Music/C/", generationModified = it.generationModified + 10)
        }
        sync.sync()
        mirror.mirror()

        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(old)
        // The favorite stays mirrored, applying to no album until its key comes back.
        assertThat(albums()).containsExactly(old)
        assertThat(LibraryLists(library).albums(favoritesOnly = true)).isEmpty()
    }

    @Test
    fun twoAlbumsThatBecomeOneKeepOneFavoriteAndTheCoverSetLast() = runBlocking<Unit> {
        val first = listOf(album(1, "Music/A/"), album(2, "Music/A/"))
        val second = listOf(album(3, "Music/B/"), album(4, "Music/B/"))
        library(first + second)
        val a = albumKeyOf(1)
        val b = albumKeyOf(3)
        favorites.setAlbum(a, favorite = true)
        covers.setAlbum(a, CoverCopy(cover("a.jpg"), 10, 10))
        now += 1_000
        favorites.setAlbum(b, favorite = true)
        covers.setAlbum(b, CoverCopy(cover("b.jpg"), 10, 10))
        mirror.mirror()

        // The second album's files move into the first one's folder.
        index.volume().rows = first + second.map { it.copy(relativePath = "Music/A/", generationModified = it.generationModified + 10) }
        sync.sync()
        assertThat(albumKeyOf(3)).isEqualTo(a)
        mirror.mirror()

        val favorite = user.favorites().favoriteAlbums().single()
        assertThat(favorite.albumKey).isEqualTo(a)
        assertThat(favorite.addedAt).isEqualTo(now - 1_000)
        covers.load()
        assertThat(covers.album(a)).isEqualTo("b.jpg")
        assertThat(covers.album(b)).isNull()
        assertThat(File(dir, "a.jpg").exists()).isFalse()
        assertThat(user.favorites().members().filter { it.albumKey == a }).hasSize(4)
    }

    @Test
    fun anAlbumLeftAloneKeepsEverything() = runBlocking<Unit> {
        val rows = listOf(album(1, "Music/A/"), album(2, "Music/A/"))
        library(rows)
        val a = albumKeyOf(1)
        favorites.setAlbum(a, favorite = true)
        covers.setAlbum(a, CoverCopy(cover("a.jpg"), 10, 10))
        mirror.mirror()
        val counter = user.user().changeCounter()

        index.volume().rows = rows + row(9)
        sync.sync()
        mirror.mirror()

        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(a)
        covers.load()
        assertThat(covers.album(a)).isEqualTo("a.jpg")
        assertThat(user.user().changeCounter()).isEqualTo(counter)
        assertThat(albums()).containsExactly(a)
    }

    @Test
    fun recentlyPlayedAndMostPlayedListTheVisibleSongsWithPlays() = runBlocking<Unit> {
        library(listOf(row(1), row(2), row(3)))
        plays.record(identityOf(1), startedAt = 500, listenedMs = 1)
        plays.record(identityOf(2), startedAt = 100, listenedMs = 1)
        plays.record(identityOf(2), startedAt = 200, listenedMs = 1)
        mirror.mirror()

        val lists = LibraryLists(library)
        assertThat(lists.recentlyPlayed(limit = 10).map { it.id }).containsExactly(fileId(1), fileId(2))
        assertThat(lists.mostPlayed(limit = 10).map { it.id }).containsExactly(fileId(2), fileId(1))
        assertThat(lists.mostPlayed(limit = 1).map { it.id }).containsExactly(fileId(2))
    }

    /** A copy of an own cover named [name] in the covers folder. */
    private fun cover(name: String): String {
        File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        return name
    }
}
