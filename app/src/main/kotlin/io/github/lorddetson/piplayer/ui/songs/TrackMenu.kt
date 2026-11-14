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

    data object Details : TrackMenuEntry
}

/** What the place's menu can do: entries it has no action for are left out. */
data class TrackMenuOffers(
    val addToPlaylist: Boolean = true,
    val favorites: Boolean = true,
    val covers: Boolean = true,
    val details: Boolean = true,
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
}
