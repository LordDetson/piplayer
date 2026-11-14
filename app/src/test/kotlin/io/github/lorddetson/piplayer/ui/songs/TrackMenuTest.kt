package io.github.lorddetson.piplayer.ui.songs

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ArtistLink
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.AddToPlaylist
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.AddToQueue
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.ChooseCover
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.Details
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.Favorite
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.GoToAlbum
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.GoToArtist
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.GoToFolder
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.PlayNext
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry.RemoveCover
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The one order of the track menu and what each place leaves out (docs/playback.md §9.1). */
class TrackMenuTest {

    private val ann = ArtistLink("ann", "Ann")
    private val bob = ArtistLink("bob", "Bob")
    private val folder = FolderKey("external_primary", "Music/Ann/")
    private val links = SongLinks("album-key", listOf(ann, bob), folder)
    private val known = TrackMenuState(links = links, favorite = true, ownCover = true)

    private fun entries(place: TrackMenuPlace, state: TrackMenuState = known, offers: TrackMenuOffers = TrackMenuOffers()) =
        TrackMenu.entries(place, offers, state)

    @Test
    fun `a song row of a list has every entry in the one order, Details last`() {
        assertThat(entries(TrackMenuPlace.LIST)).containsExactly(
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            GoToAlbum("album-key"),
            GoToArtist(ann),
            GoToArtist(bob),
            GoToFolder(folder),
            Favorite(true),
            ChooseCover,
            RemoveCover,
            Details,
        )
    }

    @Test
    fun `a playlist entry and a queue row have the same entries as a list`() {
        assertThat(entries(TrackMenuPlace.PLAYLIST)).isEqualTo(entries(TrackMenuPlace.LIST))
        assertThat(entries(TrackMenuPlace.QUEUE)).isEqualTo(entries(TrackMenuPlace.LIST))
    }

    @Test
    fun `Now Playing leaves out the queue entries and the favorite, which has its toggle`() {
        assertThat(entries(TrackMenuPlace.NOW_PLAYING)).containsExactly(
            AddToPlaylist,
            GoToAlbum("album-key"),
            GoToArtist(ann),
            GoToArtist(bob),
            GoToFolder(folder),
            ChooseCover,
            RemoveCover,
            Details,
        )
    }

    @Test
    fun `a file opened from another app has Details only`() {
        val opened = TrackMenuState(inLibrary = false)

        for (place in listOf(TrackMenuPlace.QUEUE, TrackMenuPlace.NOW_PLAYING)) {
            assertThat(entries(place, opened)).containsExactly(Details)
        }
    }

    @Test
    fun `before the links are read and without an own cover, the go-to entries and Remove own cover are left out`() {
        assertThat(entries(TrackMenuPlace.LIST, TrackMenuState())).containsExactly(
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            Favorite(false),
            ChooseCover,
            Details,
        )
    }

    @Test
    fun `entries a place has no action for are left out`() {
        val none = TrackMenuOffers(addToPlaylist = false, favorites = false, covers = false, details = false)

        assertThat(entries(TrackMenuPlace.LIST, offers = none)).containsExactly(
            PlayNext,
            AddToQueue,
            GoToAlbum("album-key"),
            GoToArtist(ann),
            GoToArtist(bob),
            GoToFolder(folder),
        )
        assertThat(entries(TrackMenuPlace.QUEUE, TrackMenuState(inLibrary = false), none)).isEmpty()
    }

    @Test
    fun `a song without an album key has no Go to album`() {
        val state = known.copy(links = links.copy(albumKey = null, artists = emptyList()))

        assertThat(entries(TrackMenuPlace.LIST, state)).doesNotContain(GoToAlbum("album-key")).contains(GoToFolder(folder))
    }
}
