package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.covers.CoverStep.Caa
import io.github.lorddetson.piplayer.covers.CoverStep.Embedded
import io.github.lorddetson.piplayer.covers.CoverStep.Saved
import io.github.lorddetson.piplayer.covers.CoverStep.Own
import io.github.lorddetson.piplayer.covers.CoverStep.Thumbnail
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The order of a cover's sources (docs/library.md §13.1). */
class CoverPlanTest {

    private fun file(
        id: Long,
        embedded: Boolean? = true,
        identity: Long? = null,
        album: String? = "a",
        decided: Long? = null,
        release: CoverRelease? = null,
    ) = CoverFile(
        id, "content://media/$id", size = 100 + id, mtime = 1_000 + id, embedded = embedded, identityId = identity, albumKey = album,
        decidedCover = decided, release = release,
    )

    private class Facts(
        val files: Map<Long, CoverFile> = emptyMap(),
        val albums: Map<String, CoverFile> = emptyMap(),
        val artistAlbums: Map<String, List<ArtistAlbum>> = emptyMap(),
        val artistFiles: Map<String, CoverFile> = emptyMap(),
        val albumCovers: Map<String, List<Long>> = emptyMap(),
        val albumReleases: Map<String, List<CoverRelease>> = emptyMap(),
    ) : CoverFacts {
        override suspend fun file(fileId: Long) = files[fileId]
        override suspend fun albumFile(albumKey: String) = albums[albumKey]
        override suspend fun artistAlbums(artistKey: String) = artistAlbums[artistKey].orEmpty()
        override suspend fun artistFile(artistKey: String) = artistFiles[artistKey]
        override suspend fun albumDecidedCovers(albumKey: String) = albumCovers[albumKey].orEmpty()
        override suspend fun albumReleases(albumKey: String) = albumReleases[albumKey].orEmpty()
    }

    /** Saved covers by row ID, their files named as SavedCovers names them. */
    private val saved = SavedCoverIndex { id -> if (id in 1L..9L) "hash$id.jpg" else null }

    private class Own(val tracks: Map<Long, String> = emptyMap(), val albums: Map<String, String> = emptyMap()) : OwnCoverIndex {
        override fun track(identityId: Long) = tracks[identityId]
        override fun album(albumKey: String) = albums[albumKey]
    }

    private fun plan(request: CoverRequest, facts: Facts, own: Own = Own(), online: Boolean = false) =
        runBlocking { CoverPlan.of(request, facts, own, saved, online) }

    @Test
    fun aSongTriesItsOwnCoverItsAlbumsTheEmbeddedPictureThenTheThumbnail() {
        val song = file(1, identity = 7)
        val facts = Facts(files = mapOf(1L to song))

        val steps = plan(CoverRequest.Track(1), facts, Own(tracks = mapOf(7L to "t.jpg"), albums = mapOf("a" to "a.jpg")))

        assertThat(steps).containsExactly(Own("t.jpg"), Own("a.jpg"), Embedded(song), Thumbnail(song))
    }

    @Test
    fun aSongWithoutOwnCoversStartsAtItsFile() {
        val song = file(1, identity = 7)

        assertThat(plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)))).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun aFileTheTagPassFoundWithoutAPictureGoesStraightToItsThumbnail() {
        val read = file(1, embedded = false)
        val unread = file(2, embedded = null)

        assertThat(plan(CoverRequest.Track(1), Facts(files = mapOf(1L to read)))).containsExactly(Thumbnail(read))
        assertThat(plan(CoverRequest.Track(2), Facts(files = mapOf(2L to unread)))).containsExactly(Embedded(unread), Thumbnail(unread))
    }

    @Test
    fun aSongWithoutAnIdentityOrAlbumHasNoOwnCover() {
        val song = file(1, identity = null, album = null)
        val own = Own(tracks = mapOf(1L to "t.jpg"), albums = mapOf("a" to "a.jpg"))

        assertThat(plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)), own)).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun aFileTheLibraryDoesNotHoldHasNoSources() {
        assertThat(plan(CoverRequest.Track(9), Facts(), Own(tracks = mapOf(9L to "t.jpg")))).isEmpty()
    }

    @Test
    fun anAlbumTriesItsOwnCoverThenTheSongThatStandsForIt() {
        val song = file(3)
        val facts = Facts(albums = mapOf("a" to song))

        assertThat(plan(CoverRequest.Album("a"), facts, Own(albums = mapOf("a" to "a.jpg")))).containsExactly(Own("a.jpg"), Embedded(song), Thumbnail(song))
        assertThat(plan(CoverRequest.Album("a"), facts)).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun aSongsOwnCoverNeverStandsForItsAlbum() {
        val song = file(3, identity = 7)

        val steps = plan(CoverRequest.Album("a"), Facts(albums = mapOf("a" to song)), Own(tracks = mapOf(7L to "t.jpg")))

        assertThat(steps).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun anAlbumWithoutSongsHasOnlyItsOwnCover() {
        assertThat(plan(CoverRequest.Album("gone"), Facts(), Own(albums = mapOf("gone" to "g.jpg")))).containsExactly(Own("g.jpg"))
        assertThat(plan(CoverRequest.Album("gone"), Facts())).isEmpty()
    }

    @Test
    fun anArtistTakesTheFirstAlbumWithAnOwnCover() {
        val first = file(1, album = "x")
        val second = file(2, album = "y")
        val facts = Facts(
            albums = mapOf("x" to first, "y" to second),
            artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", embedded = true), ArtistAlbum("y", embedded = false))),
        )

        val steps = plan(CoverRequest.Artist("ann"), facts, Own(albums = mapOf("y" to "y.jpg")))

        // The album with an embedded picture and the first album follow, in case the chosen one gives no picture.
        assertThat(steps).containsExactly(Own("y.jpg"), Embedded(second), Thumbnail(second), Embedded(first), Thumbnail(first))
    }

    @Test
    fun anArtistOtherwiseTakesTheFirstAlbumWithAnEmbeddedPictureThenTheFirstAlbum() {
        val first = file(1, album = "x", embedded = false)
        val second = file(2, album = "y")
        val albums = mapOf("x" to first, "y" to second)
        val withPicture = Facts(albums = albums, artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", false), ArtistAlbum("y", true))))
        val without = Facts(albums = albums, artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", false), ArtistAlbum("y", false))))

        assertThat(plan(CoverRequest.Artist("ann"), withPicture)).containsExactly(Embedded(second), Thumbnail(second), Thumbnail(first))
        assertThat(plan(CoverRequest.Artist("ann"), without)).containsExactly(Thumbnail(first))
    }

    @Test
    fun anArtistWithoutAlbumsTakesTheirFirstSong() {
        val song = file(5, album = null)

        val steps = plan(CoverRequest.Artist("bob"), Facts(artistFiles = mapOf("bob" to song)))

        assertThat(steps).containsExactly(Embedded(song), Thumbnail(song))
        assertThat(plan(CoverRequest.Artist("nobody"), Facts())).isEmpty()
    }

    @Test
    fun theKeysOfAFilesPicturesChangeWithTheFile() {
        val song = file(1)
        val changed = song.copy(mtime = song.mtime + 1)

        assertThat(Embedded(song).key).isNotEqualTo(Embedded(changed).key)
        assertThat(Thumbnail(song).key).isNotEqualTo(Thumbnail(changed).key)
        assertThat(Embedded(song).key).isNotEqualTo(Thumbnail(song).key)
        assertThat(Own("a.jpg").key).isNotEqualTo(Own("b.jpg").key)
    }

    // M3 (docs/library.md §13.1): the decided cover and the lazy Cover Art Archive step.

    @Test
    fun aSongsDecidedCoverComesAfterItsOwnCoversAndBeforeItsFileAndCoverArtArchiveComesLast() {
        val song = file(1, identity = 7, decided = 3, release = CoverRelease(RELEASE, GROUP))
        val own = Own(tracks = mapOf(7L to "t.jpg"), albums = mapOf("a" to "a.jpg"))

        val steps = plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)), own, online = true)

        assertThat(steps).containsExactly(
            Own("t.jpg"), Own("a.jpg"), Saved(3, "hash3.jpg"), Embedded(song), Thumbnail(song), Caa(CoverRelease(RELEASE, GROUP)),
        )
    }

    @Test
    fun aDecidedCoverWhoseRowIsGoneIsLeftOut() {
        val song = file(1, decided = 42)

        assertThat(plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)))).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun noCoverArtArchiveStepWhileOnlineLookupsAreOff() {
        val song = file(1, release = CoverRelease(RELEASE, GROUP))

        assertThat(plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)), online = false)).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun noCoverArtArchiveStepWithoutAReleaseMbid() {
        val none = file(1)
        val notAnMbid = file(2, release = CoverRelease("not an mbid", GROUP))
        val facts = Facts(files = mapOf(1L to none, 2L to notAnMbid))

        assertThat(plan(CoverRequest.Track(1), facts, online = true)).containsExactly(Embedded(none), Thumbnail(none))
        assertThat(plan(CoverRequest.Track(2), facts, online = true)).containsExactly(Embedded(notAnMbid), Thumbnail(notAnMbid))
    }

    @Test
    fun theCoverArtArchiveStepTakesPlainMbidsAndDropsAReleaseGroupThatIsNone() {
        val song = file(1, embedded = false, release = CoverRelease(" ${RELEASE.uppercase()} ", "junk"))

        val steps = plan(CoverRequest.Track(1), Facts(files = mapOf(1L to song)), online = true)

        assertThat(steps).containsExactly(Thumbnail(song), Caa(CoverRelease(RELEASE, null)))
    }

    @Test
    fun anAlbumTakesItsOwnCoverItsDecidedCoverASongsPictureThenItsRelease() {
        val song = file(3)
        val facts = Facts(
            albums = mapOf("a" to song),
            albumCovers = mapOf("a" to listOf(5L)),
            albumReleases = mapOf("a" to listOf(CoverRelease(RELEASE, GROUP))),
        )

        val steps = plan(CoverRequest.Album("a"), facts, Own(albums = mapOf("a" to "a.jpg")), online = true)

        assertThat(steps).containsExactly(Own("a.jpg"), Saved(5, "hash5.jpg"), Embedded(song), Thumbnail(song), Caa(CoverRelease(RELEASE, GROUP)))
        assertThat(plan(CoverRequest.Album("a"), facts, online = false)).containsExactly(Saved(5, "hash5.jpg"), Embedded(song), Thumbnail(song))
    }

    @Test
    fun anAlbumWithoutSongsStillHasItsReleaseWhileOnline() {
        val facts = Facts(albumReleases = mapOf("a" to listOf(CoverRelease(RELEASE))))

        assertThat(plan(CoverRequest.Album("a"), facts, online = true)).containsExactly(Caa(CoverRelease(RELEASE)))
    }

    @Test
    fun aSongsDecidedCoverOrReleaseIsNotTheAlbumsUnlessTheAlbumFactsSaySo() {
        // The album's facts pick its songs' decided cover and release by disc, track and file name; the stand-in song's
        // own ones are not read for it.
        val song = file(3, decided = 4, release = CoverRelease(RELEASE))

        assertThat(plan(CoverRequest.Album("a"), Facts(albums = mapOf("a" to song)), online = true)).containsExactly(Embedded(song), Thumbnail(song))
    }

    @Test
    fun anArtistTakesTheFirstAlbumWithADecidedCoverBeforeOneWithAnEmbeddedPicture() {
        val first = file(1, album = "x")
        val second = file(2, album = "y")
        val facts = Facts(
            albums = mapOf("x" to first, "y" to second),
            albumCovers = mapOf("y" to listOf(6L)),
            artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", embedded = true), ArtistAlbum("y", embedded = false, decided = true))),
        )

        // A decided cover whose file is gone gives way to the album with an embedded picture.
        assertThat(plan(CoverRequest.Artist("ann"), facts)).containsExactly(
            Saved(6, "hash6.jpg"), Embedded(second), Thumbnail(second), Embedded(first), Thumbnail(first),
        )
        // An own cover still comes first, each album once.
        assertThat(plan(CoverRequest.Artist("ann"), facts, Own(albums = mapOf("x" to "x.jpg")))).containsExactly(
            Own("x.jpg"), Embedded(first), Thumbnail(first), Saved(6, "hash6.jpg"), Embedded(second), Thumbnail(second),
        )
    }

    @Test
    fun anArtistsLocalPicturesComeBeforeTheCoverArtArchiveStepOfTheFirstAlbumWithARelease() {
        // The first album may have a folder image as its thumbnail, which no fact shows: turning online lookups on must not
        // replace it with another album's Cover Art Archive cover.
        val first = file(1, album = "x", embedded = false)
        val second = file(2, album = "y", embedded = false)
        val facts = Facts(
            albums = mapOf("x" to first, "y" to second),
            albumReleases = mapOf("y" to listOf(CoverRelease(RELEASE))),
            artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", false), ArtistAlbum("y", false, release = true))),
        )

        assertThat(plan(CoverRequest.Artist("ann"), facts, online = true)).containsExactly(Thumbnail(first), Caa(CoverRelease(RELEASE)))
        assertThat(plan(CoverRequest.Artist("ann"), facts, online = false)).containsExactly(Thumbnail(first))
    }

    @Test
    fun anArtistsCoverArtArchiveStepComesLastAfterAnAlbumWithAPictureAndSkipsAReleaseThatIsNotAnMbid() {
        val first = file(1, album = "x", embedded = false)
        val second = file(2, album = "y")
        val third = file(3, album = "z", embedded = false)
        val facts = Facts(
            albums = mapOf("x" to first, "y" to second, "z" to third),
            albumReleases = mapOf("x" to listOf(CoverRelease("not an mbid")), "z" to listOf(CoverRelease(RELEASE, GROUP))),
            artistAlbums = mapOf(
                "ann" to listOf(ArtistAlbum("x", false, release = true), ArtistAlbum("y", true), ArtistAlbum("z", false, release = true)),
            ),
        )

        assertThat(plan(CoverRequest.Artist("ann"), facts, online = true)).containsExactly(
            Embedded(second), Thumbnail(second), Thumbnail(first), Caa(CoverRelease(RELEASE, GROUP)),
        )
    }

    @Test
    fun anAlbumTriesEachDecidedCoverOfItsSongsAndTakesTheFirstReleaseThatIsAnMbid() {
        val song = file(3)
        val facts = Facts(
            albums = mapOf("a" to song),
            // 42 has no row; 5 may have lost its file, which only the load can tell.
            albumCovers = mapOf("a" to listOf(5L, 42L, 6L)),
            albumReleases = mapOf("a" to listOf(CoverRelease("junk"), CoverRelease(RELEASE, GROUP))),
        )

        assertThat(plan(CoverRequest.Album("a"), facts, online = true)).containsExactly(
            Saved(5, "hash5.jpg"), Saved(6, "hash6.jpg"), Embedded(song), Thumbnail(song), Caa(CoverRelease(RELEASE, GROUP)),
        )
    }

    @Test
    fun anArtistWithoutAlbumsTakesTheDecidedCoverAndReleaseOfTheirFirstSongButNotItsOwnCovers() {
        val song = file(5, album = null, identity = 9, decided = 2, release = CoverRelease(RELEASE))

        val steps = plan(CoverRequest.Artist("bob"), Facts(artistFiles = mapOf("bob" to song)), Own(tracks = mapOf(9L to "t.jpg")), online = true)

        assertThat(steps).containsExactly(Saved(2, "hash2.jpg"), Embedded(song), Thumbnail(song), Caa(CoverRelease(RELEASE)))
    }

    @Test
    fun theKeysOfSavedAndCoverArtArchiveStepsNameTheirPictures() {
        assertThat(Saved(1, "aa.jpg").key).isEqualTo("saved/aa.jpg")
        // A row ID used again for other bytes names another file, so another picture.
        assertThat(Saved(1, "aa.jpg").key).isNotEqualTo(Saved(1, "bb.jpg").key)
        assertThat(Caa(CoverRelease(RELEASE, GROUP)).key).isEqualTo("caa/$RELEASE/$GROUP")
        assertThat(Caa(CoverRelease(RELEASE)).key).isEqualTo("caa/$RELEASE/-")
        assertThat(Caa(CoverRelease(RELEASE)).remote).isTrue()
        assertThat(Saved(1, "aa.jpg").remote).isFalse()
        assertThat(Embedded(file(1)).remote).isFalse()
    }

    private companion object {
        const val RELEASE = "11111111-1111-4111-8111-111111111111"
        const val GROUP = "22222222-2222-4222-8222-222222222222"
    }
}
