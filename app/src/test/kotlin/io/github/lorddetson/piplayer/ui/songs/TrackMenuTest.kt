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
    fun `Identify comes right before Details wherever a song has a menu, Now Playing's included`() {
        val identify = TrackMenuOffers(identify = true)

        for (place in TrackMenuPlace.entries) {
            val withIdentify = entries(place, offers = identify)
            assertThat(withIdentify).endsWith(TrackMenuEntry.Identify, Details)
            assertThat(withIdentify - TrackMenuEntry.Identify).isEqualTo(entries(place))
        }
        // Without Details, Identify ends the song's entries.
        assertThat(entries(TrackMenuPlace.LIST, offers = TrackMenuOffers(details = false, identify = true))).endsWith(RemoveCover, TrackMenuEntry.Identify)
    }

    @Test
    fun `Edit tags is offered for library tracks immediately before Details`() {
        val offers = TrackMenuOffers(identify = true, editTags = true)
        for (place in TrackMenuPlace.entries) {
            assertThat(entries(place, offers = offers)).endsWith(TrackMenuEntry.Identify, TrackMenuEntry.EditTags, Details)
            assertThat(entries(place, TrackMenuState(inLibrary = false), offers)).doesNotContain(TrackMenuEntry.EditTags)
        }
        val one = SelectionMenuState(rows = 1, songs = 1)
        assertThat(selection(SelectionPlace.LIST, one, offers)).doesNotContain(TrackMenuEntry.EditTags)
    }

    @Test
    fun `a file opened from another app has Identify and Details`() {
        val opened = TrackMenuState(inLibrary = false)

        for (place in listOf(TrackMenuPlace.QUEUE, TrackMenuPlace.NOW_PLAYING)) {
            assertThat(entries(place, opened, TrackMenuOffers(identify = true))).containsExactly(TrackMenuEntry.Identify, Details)
        }
    }

    @Test
    fun `a selection's menu has Identify right before Details when batches can start`() {
        val one = SelectionMenuState(rows = 1, songs = 1, favorites = FavoriteBatch(counted = 1, notFavorite = 0))
        val identify = TrackMenuOffers(identify = true)

        for (place in SelectionPlace.entries) {
            val withIdentify = selection(place, one, identify)
            assertThat(withIdentify - TrackMenuEntry.Identify).isEqualTo(selection(place, one))
            assertThat(withIdentify.indexOf(TrackMenuEntry.Identify)).isEqualTo(withIdentify.indexOf(Details) - 1)
        }
        // No library song in the selection: nothing to start a batch over.
        assertThat(selection(SelectionPlace.LIST, SelectionMenuState(rows = 2, songs = 0), identify))
            .doesNotContain(TrackMenuEntry.Identify)
    }

    @Test
    fun `a song without an album key has no Go to album`() {
        val state = known.copy(links = links.copy(albumKey = null, artists = emptyList()))

        assertThat(entries(TrackMenuPlace.LIST, state)).doesNotContain(GoToAlbum("album-key")).contains(GoToFolder(folder))
    }

    private fun selection(place: SelectionPlace, state: SelectionMenuState, offers: TrackMenuOffers = TrackMenuOffers()) =
        TrackMenu.selectionEntries(place, offers, state)

    private val severalNoneFavorite = SelectionMenuState(rows = 3, songs = 3, favorites = FavoriteBatch(counted = 3, notFavorite = 3))

    @Test
    fun `a selection of a list has Play first, then the track menu's entries for many songs, without Details`() {
        assertThat(selection(SelectionPlace.LIST, severalNoneFavorite)).containsExactly(
            TrackMenuEntry.Play,
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            Favorite(false),
        )
    }

    @Test
    fun `one selected song also has Details, never the places or covers of the song`() {
        val one = SelectionMenuState(rows = 1, songs = 1, favorites = FavoriteBatch(counted = 1, notFavorite = 0))

        assertThat(selection(SelectionPlace.LIST, one)).containsExactly(
            TrackMenuEntry.Play,
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            Favorite(true),
            Details,
        )
    }

    @Test
    fun `a playlist selection ends with Remove from playlist, and Details only for one available entry`() {
        assertThat(selection(SelectionPlace.PLAYLIST, severalNoneFavorite)).containsExactly(
            TrackMenuEntry.Play,
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            Favorite(false),
            TrackMenuEntry.RemoveFromPlaylist,
        )
        val unavailable = SelectionMenuState(rows = 1, songs = 0)
        assertThat(selection(SelectionPlace.PLAYLIST, unavailable)).containsExactly(TrackMenuEntry.RemoveFromPlaylist)
    }

    @Test
    fun `a queue selection has the row menu's queue entries and no Play, then Remove from queue`() {
        assertThat(selection(SelectionPlace.QUEUE, severalNoneFavorite)).containsExactly(
            PlayNext,
            AddToQueue,
            AddToPlaylist,
            Favorite(false),
            TrackMenuEntry.RemoveFromQueue,
        )
        // One entry has Details, also a file opened from another app, which has nothing that needs a library song.
        val opened = SelectionMenuState(rows = 1, songs = 0)
        assertThat(selection(SelectionPlace.QUEUE, opened)).containsExactly(Details, TrackMenuEntry.RemoveFromQueue)
    }

    @Test
    fun `favorites are Add while one counted song is not one, Remove while all counted are, unread songs aside`() {
        val oneNot = SelectionMenuState(rows = 4, songs = 4, favorites = FavoriteBatch(counted = 3, notFavorite = 1))
        val allAre = SelectionMenuState(rows = 4, songs = 4, favorites = FavoriteBatch(counted = 3, notFavorite = 0))

        assertThat(selection(SelectionPlace.LIST, oneNot)).contains(Favorite(false))
        assertThat(selection(SelectionPlace.LIST, allAre)).contains(Favorite(true))
    }

    @Test
    fun `favorites wait while they are read and stay off when no selected song has its tags read`() {
        val reading = SelectionMenuState(rows = 2, songs = 2, favorites = null)
        val unread = SelectionMenuState(rows = 2, songs = 2, favorites = FavoriteBatch(counted = 0, notFavorite = 0))

        assertThat(selection(SelectionPlace.LIST, reading)).contains(TrackMenuEntry.FavoriteUnavailable(reading = true))
            .doesNotContain(Favorite(false), Favorite(true))
        assertThat(selection(SelectionPlace.LIST, unread)).contains(TrackMenuEntry.FavoriteUnavailable(reading = false))
    }

    @Test
    fun `a selection's entries a place has no action for are left out`() {
        val none = TrackMenuOffers(addToPlaylist = false, favorites = false, covers = false, details = false)
        val one = SelectionMenuState(rows = 1, songs = 1, favorites = FavoriteBatch(1, 1))

        assertThat(selection(SelectionPlace.LIST, one, none)).containsExactly(TrackMenuEntry.Play, PlayNext, AddToQueue)
    }
}
