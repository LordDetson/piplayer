package io.github.lorddetson.piplayer.covers

import io.github.lorddetson.piplayer.covers.CoverStep.Embedded
import io.github.lorddetson.piplayer.covers.CoverStep.Own
import io.github.lorddetson.piplayer.covers.CoverStep.Thumbnail
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The order of a cover's sources (docs/library.md §13.1). */
class CoverPlanTest {

    private fun file(id: Long, embedded: Boolean? = true, identity: Long? = null, album: String? = "a") =
        CoverFile(id, "content://media/$id", size = 100 + id, mtime = 1_000 + id, embedded = embedded, identityId = identity, albumKey = album)

    private class Facts(
        val files: Map<Long, CoverFile> = emptyMap(),
        val albums: Map<String, CoverFile> = emptyMap(),
        val artistAlbums: Map<String, List<ArtistAlbum>> = emptyMap(),
        val artistFiles: Map<String, CoverFile> = emptyMap(),
    ) : CoverFacts {
        override suspend fun file(fileId: Long) = files[fileId]
        override suspend fun albumFile(albumKey: String) = albums[albumKey]
        override suspend fun artistAlbums(artistKey: String) = artistAlbums[artistKey].orEmpty()
        override suspend fun artistFile(artistKey: String) = artistFiles[artistKey]
    }

    private class Own(val tracks: Map<Long, String> = emptyMap(), val albums: Map<String, String> = emptyMap()) : OwnCoverIndex {
        override fun track(identityId: Long) = tracks[identityId]
        override fun album(albumKey: String) = albums[albumKey]
    }

    private fun plan(request: CoverRequest, facts: Facts, own: Own = Own()) = runBlocking { CoverPlan.of(request, facts, own) }

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

        assertThat(steps).containsExactly(Own("y.jpg"), Embedded(second), Thumbnail(second))
    }

    @Test
    fun anArtistOtherwiseTakesTheFirstAlbumWithAnEmbeddedPictureThenTheFirstAlbum() {
        val first = file(1, album = "x", embedded = false)
        val second = file(2, album = "y")
        val albums = mapOf("x" to first, "y" to second)
        val withPicture = Facts(albums = albums, artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", false), ArtistAlbum("y", true))))
        val without = Facts(albums = albums, artistAlbums = mapOf("ann" to listOf(ArtistAlbum("x", false), ArtistAlbum("y", false))))

        assertThat(plan(CoverRequest.Artist("ann"), withPicture)).containsExactly(Embedded(second), Thumbnail(second))
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
}
