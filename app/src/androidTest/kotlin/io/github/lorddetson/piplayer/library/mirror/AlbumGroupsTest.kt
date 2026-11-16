package io.github.lorddetson.piplayer.library.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.covers.CoverCopy
import io.github.lorddetson.piplayer.covers.OwnCovers
import io.github.lorddetson.piplayer.library.FakeMediaIndex
import io.github.lorddetson.piplayer.library.FakeMediaIndex.Companion.row
import io.github.lorddetson.piplayer.library.FakeRules
import io.github.lorddetson.piplayer.library.db.LibraryDatabase
import io.github.lorddetson.piplayer.library.identity.Hex
import io.github.lorddetson.piplayer.library.identity.IdentityAttacher
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.mbid
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The album key with decided values (docs/library.md §6.2; §11.2 M3 "Album groups on the real databases"): a fake media
 * index, the real databases, the real sync, attach pass, decision mirror and mirror of favorites and own covers.
 */
@RunWith(AndroidJUnit4::class)
class AlbumGroupsTest {

    private val context = TestFiles.targetContext
    private val library = LibraryDatabase.open(context, file = null)
    private val user = UserDatabase.open(context, name = null)
    private val index = FakeMediaIndex()
    private val sync = LibrarySync(library, index, mayRead = { true }, FakeRules())
    private val now = 1_800_000_000_000L
    private val attacher = IdentityAttacher(library, user, sync, holders = { user.user().identitiesHoldingData().toSet() })
    private val dir = File(context.cacheDir, "album-group-covers").apply { deleteRecursively(); mkdirs() }
    private val covers = OwnCovers(user, dir) { now }
    private val userMirror = UserMirror(library, user, sync, covers, clock = { now })
    private val mirror = DecisionMirror(library, user, sync)
    private val decisions = LibraryDecisions(Decisions(user)) { mirror }
    private val favorites = Favorites(user) { now }
    private val release = mbid(1, 0x1e1)

    @After
    fun close() {
        mirror.close()
        userMirror.close()
        attacher.close()
        sync.close()
        library.close()
        user.close()
        dir.deleteRecursively()
    }

    private fun key(n: Long) = "%064x".format(n)

    private suspend fun fileId(mediaStoreId: Long) = library.library().files().single { it.mediaStoreId == mediaStoreId }.id

    private suspend fun albumKeyOf(mediaStoreId: Long): String? = LibraryLists(library).links(fileId(mediaStoreId))?.albumKey

    private suspend fun identityOf(n: Long) = checkNotNull(user.identities().idOfKey(Hex.bytes(key(n))))

    private fun track(id: Long, path: String = "Music/Record/") =
        row(id, path = path, album = "Record", artist = "Ann", albumArtist = "Ann", generation = id)

    /** Syncs [rows], gives each file the key of its media ID and attaches identities. */
    private suspend fun library(rows: List<IndexRow>) {
        index.volume().rows = rows
        sync.sync()
        for (row in rows) library.library().setContent(fileId(row.mediaStoreId), key(row.mediaStoreId), 200_000, DurationSource.HEADER)
        attacher.attach()
    }

    private suspend fun identify(mediaStoreId: Long, album: String = "Record") =
        checkNotNull(decisions.apply(request(key(mediaStoreId), details(album = album, release = 1, recording = mediaStoreId.toInt()), fileId = fileId(mediaStoreId))))

    private fun cover(name: String): String {
        File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        return name
    }

    @Test
    fun identifyingOneTrackOfATaggedAlbumKeepsTheAlbumItsFavoriteAndOwnCoverAndTheTracksOwn() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3), track(4, path = "Music/Other/")))
        val album = checkNotNull(albumKeyOf(1))
        favorites.setAlbum(album, favorite = true)
        covers.setAlbum(album, CoverCopy(cover("album.jpg"), 10, 10))
        favorites.set(identityOf(1), favorite = true)
        covers.setTrack(identityOf(1), CoverCopy(cover("track.jpg"), 10, 10))
        userMirror.mirror()

        // The identification decides another album title and a release that the other tracks do not have.
        identify(1, album = "Record (Deluxe Edition)")
        userMirror.mirror()

        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo(album)
        assertThat(LibraryLists(library).album(album)?.songs).isEqualTo(3)
        assertThat(LibraryLists(library).albums().map { it.key }).containsExactlyInAnyOrder(album, albumKeyOf(4))
        assertThat(library.library().displays().single { it.fileId == fileId(1) }.album).isEqualTo("Record (Deluxe Edition)")
        assertThat(user.favorites().favoriteAlbums().map { it.albumKey }).containsExactly(album)
        covers.load()
        assertThat(covers.album(album)).isEqualTo("album.jpg")
        assertThat(favorites.isFavorite(identityOf(1))).isTrue()
        assertThat(covers.track(identityOf(1))).isEqualTo("track.jpg")
    }

    @Test
    fun identifyingEveryTrackToOneReleaseGivesThemTheReleasesKeyAlsoThroughAWriteOfEveryRow() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3, path = "Music/Record/CD2/"), track(4, path = "Music/Other/")))
        val own = checkNotNull(albumKeyOf(1))
        val other = albumKeyOf(4)

        identify(1)
        identify(2)
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track before the last").isEqualTo(own)
        identify(3)

        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo("mbid:$release")
        assertThat(albumKeyOf(4)).isEqualTo(other)
        assertThat(LibraryLists(library).album("mbid:$release")?.songs).isEqualTo(3)

        // A write of every display row settles the groups from what it resolved.
        val all = library.library().files().map { it.id }
        TrackDisplayWriter(library).refresh(all)
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track after the refresh").isEqualTo("mbid:$release")
        // So does a write of one of them.
        TrackDisplayWriter(library).refresh(listOf(fileId(2)))
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track after one row").isEqualTo("mbid:$release")
    }

    @Test
    fun anUndoBringsTheGroupsOwnKeyBack() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3)))
        val own = checkNotNull(albumKeyOf(1))
        identify(1)
        val second = identify(2)
        identify(3)
        assertThat(albumKeyOf(1)).isEqualTo("mbid:$release")

        decisions.undo(second.decisionId)

        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo(own)
    }

    @Test
    fun theRestOfAGroupTakesTheReleaseWhenItsLastFileWithoutItLeaves() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3)))
        val own = checkNotNull(albumKeyOf(1))
        identify(1)
        identify(2)
        assertThat(albumKeyOf(1)).isEqualTo(own)

        // File 3's album changes: it leaves the group, whose other files all end up with the release.
        index.volume().rows = listOf(track(1), track(2), track(3).copy(album = "Another record", generationModified = 30))
        sync.sync()

        assertThat(albumKeyOf(1)).isEqualTo("mbid:$release")
        assertThat(albumKeyOf(2)).isEqualTo("mbid:$release")
        assertThat(albumKeyOf(3)).isNotEqualTo(own).isNotEqualTo("mbid:$release")
    }

    @Test
    fun aNewFileInTheAlbumsFolderBringsTheGroupsOwnKeyBack() = runBlocking<Unit> {
        library(listOf(track(1), track(2)))
        val own = checkNotNull(albumKeyOf(1))
        identify(1)
        identify(2)
        assertThat(albumKeyOf(2)).isEqualTo("mbid:$release")

        index.volume().rows = listOf(track(1), track(2), track(5))
        sync.sync()

        for (track in listOf(1L, 2L, 5L)) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo(own)
    }
}
