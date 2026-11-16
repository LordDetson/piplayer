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
import io.github.lorddetson.piplayer.library.sync.AlbumGroupKeys
import io.github.lorddetson.piplayer.library.sync.IndexRow
import io.github.lorddetson.piplayer.library.sync.LibrarySync
import io.github.lorddetson.piplayer.library.sync.TrackDisplayWriter
import io.github.lorddetson.piplayer.library.tagpass.DurationSource
import io.github.lorddetson.piplayer.library.values.DecidedField
import io.github.lorddetson.piplayer.settings.ProtectedField
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.details
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.mbid
import io.github.lorddetson.piplayer.userdata.DecisionFixtures.request
import io.github.lorddetson.piplayer.userdata.Decisions
import io.github.lorddetson.piplayer.userdata.Favorites
import io.github.lorddetson.piplayer.userdata.UserDatabase
import java.io.File
import java.util.Collections
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
        AlbumGroupKeys.onGroupsRead = {}
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

    private suspend fun identify(mediaStoreId: Long, album: String = "Record", release: Int = 1) =
        checkNotNull(decisions.apply(request(key(mediaStoreId), details(album = album, release = release, recording = mediaStoreId.toInt()), fileId = fileId(mediaStoreId))))

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
    fun aWriteOfAnIdentifiedTrackReadsNoFileOfItsGroupWhileAnotherFileHasNeitherADecidedAlbumNorRelease() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3)))
        val own = checkNotNull(albumKeyOf(1))
        // Only this test's library is written meanwhile; a write elsewhere names other keys.
        val read = Collections.synchronizedList(ArrayList<String>())
        AlbumGroupKeys.onGroupsRead = { read += it }

        // Track 3 has no decided value: the group keeps its own key, and its files are not read.
        identify(1)
        identify(2)
        assertThat(read).describedAs("groups read while track 3 has no decided value").doesNotContain(own)
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track before the last").isEqualTo(own)

        // Every other file of the group has a decided album: the group can move, so it is read.
        identify(3)
        assertThat(read).describedAs("groups read by the last write").contains(own)
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo("mbid:$release")
    }

    @Test
    fun aFileWhoseKeyComesFromItsDecidedValuesDoesNotKeepTheGroupWhoseKeyItHoldsFromMoving() = runBlocking<Unit> {
        // Track 9 has no album of its own; an identification without the release decides the album of the others.
        library(listOf(track(1), track(2), row(9, path = "Music/Record/", album = null, artist = "Ann", generation = 9)))
        val own = checkNotNull(albumKeyOf(1))
        checkNotNull(
            decisions.apply(
                request(key(9), details(recording = 9), fields = setOf(ProtectedField.ALBUM, ProtectedField.ALBUM_ARTIST), fileId = fileId(9)),
            ),
        )
        assertThat(albumKeyOf(9)).describedAs("the key of track 9's decided values").isEqualTo(own)

        identify(1)
        identify(2)

        // Track 9 is of no group and has no release: the group of tracks 1 and 2 takes theirs.
        assertThat(albumKeyOf(1)).isEqualTo("mbid:$release")
        assertThat(albumKeyOf(2)).isEqualTo("mbid:$release")
        assertThat(albumKeyOf(9)).isEqualTo(own)
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

    @Test
    fun aUserEditOfTheAlbumMergesATrackIntoTheAlbumItNamesAndLeavesTheAlbumItCameFrom() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3).copy(album = "Record (Remastered)")))
        val record = checkNotNull(albumKeyOf(1))
        val remastered = checkNotNull(albumKeyOf(3))
        assertThat(remastered).isNotEqualTo(record)

        checkNotNull(decisions.edit(identityOf(3), mapOf(DecidedField.ALBUM to "Record")))

        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo(record)
        assertThat(LibraryLists(library).albums().map { it.key }).containsExactly(record)
        assertThat(LibraryLists(library).album(record)?.songs).isEqualTo(3)
    }

    @Test
    fun aTrackAUserEditMergedIntoAnAlbumCountsForItsGroupWhenAnotherTrackIsIdentified() = runBlocking<Unit> {
        library(listOf(track(1), track(2), track(3).copy(album = "Record (Remastered)")))
        val record = checkNotNull(albumKeyOf(1))
        // The odd track is identified first and then edited into the album: the newest decision wins the album field
        // (TI §7.1), so the edit counts. The last write then holds another track and reads the edited one's row.
        identify(3)
        checkNotNull(decisions.edit(identityOf(3), mapOf(DecidedField.ALBUM to "Record")))
        identify(1)
        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track before the last").isEqualTo(record)
        identify(2)

        for (track in 1L..3L) assertThat(albumKeyOf(track)).describedAs("track $track").isEqualTo("mbid:$release")
    }

    @Test
    fun newFilesInTheFoldersOfMoreThanSixteenIdentifiedAlbumsBringEachGroupsOwnKeyBack() = runBlocking<Unit> {
        val albums = 1..17
        fun folder(album: Int) = "Music/Album %02d/".format(album)
        fun albumTrack(id: Long, album: Int) =
            row(id, path = folder(album), album = "Album $album", artist = "Ann", albumArtist = "Ann", generation = id)
        val identified = albums.flatMap { album -> listOf(albumTrack(album * 2L - 1, album), albumTrack(album * 2L, album)) }
        // A file with a decided release in a folder of its own, which no group of the write is in.
        val elsewhere = albumTrack(99, 99)
        library(identified + elsewhere)
        val own = albums.associateWith { album -> checkNotNull(albumKeyOf(album * 2L)) }
        for (album in albums) {
            identify(album * 2L - 1, album = "Album $album", release = album)
            identify(album * 2L, album = "Album $album", release = album)
        }
        identify(99, album = "Album 99", release = 99)
        for (album in albums) {
            assertThat(albumKeyOf(album * 2L)).describedAs("album $album identified").isEqualTo("mbid:${mbid(album, 0x1e1)}")
        }
        val elsewhereKey = albumKeyOf(99)

        // One sync brings a new file into the folder of every album: no row holds the groups' own keys.
        index.volume().rows = identified + elsewhere + albums.map { album -> albumTrack(100L + album, album) }
        sync.sync()

        for (album in albums) {
            for (track in listOf(album * 2L - 1, album * 2L, 100L + album)) {
                assertThat(albumKeyOf(track)).describedAs("album $album, track $track").isEqualTo(own.getValue(album))
            }
        }
        assertThat(albumKeyOf(99)).isEqualTo(elsewhereKey)
    }
}
