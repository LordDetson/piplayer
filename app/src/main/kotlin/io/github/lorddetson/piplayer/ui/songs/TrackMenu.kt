package io.github.lorddetson.piplayer.ui.songs

import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.ArtistLink
import io.github.lorddetson.piplayer.library.lists.SongLinks

/** Where a song's menu is (docs/playback.md §9.1). */
enum class TrackMenuPlace {
    /** A song row of a list or page of the library. */
    LIST,

    /** A row of a playlist page, before the playlist's own entries. */
    PLAYLIST,

    /** A row of the queue screen, before "Remove from queue". */
    QUEUE,

    /** Now Playing's "More options". */
    NOW_PLAYING,
}

/** An entry of the track menu (docs/playback.md §9.1). */
sealed interface TrackMenuEntry {
    data object PlayNext : TrackMenuEntry

    data object AddToQueue : TrackMenuEntry

    data object AddToPlaylist : TrackMenuEntry

    data class GoToAlbum(val key: String) : TrackMenuEntry

    data class GoToArtist(val artist: ArtistLink) : TrackMenuEntry

    data class GoToFolder(val folder: FolderKey) : TrackMenuEntry

    /** "Add to favorites", or "Remove from favorites" when [favorite]. */
    data class Favorite(val favorite: Boolean) : TrackMenuEntry

    data object ChooseCover : TrackMenuEntry

    data object RemoveCover : TrackMenuEntry

    /** "Identify", which opens the review of the song or the opened file (TI §1, §6). */
    data object Identify : TrackMenuEntry

    data object Details : TrackMenuEntry

    /** "Play" of the selected songs (docs/library.md §20.3). */
    data object Play : TrackMenuEntry

    /**
     * The favorite entry of a selection that cannot act now: still [reading] which songs are favorites, or none of the
     * selected songs has its tags read (docs/library.md §20.3); shown disabled.
     */
    data class FavoriteUnavailable(val reading: Boolean) : TrackMenuEntry

    /** "Remove from playlist" of the selected entries of a playlist page. */
    data object RemoveFromPlaylist : TrackMenuEntry

    /** "Remove from queue" of the selected entries of the queue screen (docs/playback.md §9.6). */
    data object RemoveFromQueue : TrackMenuEntry
}

/** Where a selection's menu is (docs/library.md §20.3). */
enum class SelectionPlace {
    /** A song list or page of the library. */
    LIST,

    /** A playlist page. */
    PLAYLIST,

    /** The queue screen. */
    QUEUE,
}

/** Of the counted selected songs (those with their tags read), how many are not favorites (docs/library.md §20.3). */
data class FavoriteBatch(val counted: Int, val notFavorite: Int)

/**
 * What is known of a selection when its menu opens: how many [rows] are selected, how many of them are [songs] of the
 * library (not an unavailable playlist entry, not a file opened from another app), and [favorites], null while it is read.
 */
data class SelectionMenuState(val rows: Int, val songs: Int, val favorites: FavoriteBatch? = null)

/** What the place's menu can do: entries it has no action for are left out. */
data class TrackMenuOffers(
    val addToPlaylist: Boolean = true,
    val favorites: Boolean = true,
    val covers: Boolean = true,
    val details: Boolean = true,
    /** "Identify" (TI §1): the place can open the review. */
    val identify: Boolean = false,
)

/**
 * What is known of the song when its menu opens: [inLibrary] is false for a file opened from another app, [links] where
 * the song belongs (read when the menu opens), [favorite] and [ownCover] its state.
 */
data class TrackMenuState(
    val inLibrary: Boolean = true,
    val links: SongLinks? = null,
    val favorite: Boolean = false,
    val ownCover: Boolean = false,
)

/** The entries of the track menu in their one order (docs/playback.md §9.1). */
object TrackMenu {

    fun entries(place: TrackMenuPlace, offers: TrackMenuOffers, state: TrackMenuState): List<TrackMenuEntry> = buildList {
        // A file opened from another app has no library row to add, go to or keep anything for.
        if (!state.inLibrary) {
            if (offers.details) add(TrackMenuEntry.Details)
            return@buildList
        }
        // On Now Playing the song is the one playing, and its favorite has the toggle beside the menu.
        val nowPlaying = place == TrackMenuPlace.NOW_PLAYING
        if (!nowPlaying) {
            add(TrackMenuEntry.PlayNext)
            add(TrackMenuEntry.AddToQueue)
        }
        if (offers.addToPlaylist) add(TrackMenuEntry.AddToPlaylist)
        state.links?.albumKey?.let { add(TrackMenuEntry.GoToAlbum(it)) }
        state.links?.artists?.forEach { add(TrackMenuEntry.GoToArtist(it)) }
        state.links?.folder?.let { add(TrackMenuEntry.GoToFolder(it)) }
        if (offers.favorites && !nowPlaying) add(TrackMenuEntry.Favorite(state.favorite))
        if (offers.covers) {
            add(TrackMenuEntry.ChooseCover)
            if (state.ownCover) add(TrackMenuEntry.RemoveCover)
        }
        // "Identify" (M3) and "Edit tags" (M4b) will come here, before "Details".
        if (offers.details) add(TrackMenuEntry.Details)
    }

    /**
     * The entries of a selection's menu (docs/library.md §20.3): the track menu's entries that make sense for many songs,
     * in its order with "Play" first, then the place's own.
     */
    fun selectionEntries(place: SelectionPlace, offers: TrackMenuOffers, state: SelectionMenuState): List<TrackMenuEntry> = buildList {
        // Unavailable entries and opened files have no library song to play, add or keep anything for.
        if (state.songs > 0) {
            // On the queue screen "Play" would replace the queue with a part of itself (docs/playback.md §9.6).
            if (place != SelectionPlace.QUEUE) add(TrackMenuEntry.Play)
            add(TrackMenuEntry.PlayNext)
            add(TrackMenuEntry.AddToQueue)
            if (offers.addToPlaylist) add(TrackMenuEntry.AddToPlaylist)
            if (offers.favorites) {
                val favorites = state.favorites
                add(
                    when {
                        favorites == null -> TrackMenuEntry.FavoriteUnavailable(reading = true)
                        favorites.counted == 0 -> TrackMenuEntry.FavoriteUnavailable(reading = false)
                        else -> TrackMenuEntry.Favorite(favorite = favorites.notFavorite == 0)
                    },
                )
            }
        }
        // One song's details; on the queue screen also a file opened from another app's.
        if (offers.details && state.rows == 1 && (state.songs == 1 || place == SelectionPlace.QUEUE)) add(TrackMenuEntry.Details)
        when (place) {
            SelectionPlace.LIST -> Unit
            SelectionPlace.PLAYLIST -> add(TrackMenuEntry.RemoveFromPlaylist)
            SelectionPlace.QUEUE -> add(TrackMenuEntry.RemoveFromQueue)
        }
    }
}
