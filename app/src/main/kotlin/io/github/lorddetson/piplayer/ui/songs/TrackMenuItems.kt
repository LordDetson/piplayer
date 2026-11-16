package io.github.lorddetson.piplayer.ui.songs

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify

/**
 * The entries of the track menu inside an open dropdown (docs/playback.md §9.1): of the library's [song], or of the file
 * opened from another app at [openedUri] when [song] is null. What the menu needs to know of the song (its places, its
 * favorite, its own cover) is read off the main thread each time the menu opens, since a dropdown composes its entries
 * only while it is open. Each entry closes the menu by [close] before it acts. "Identify" shows where the app provides
 * [LocalIdentify].
 */
@Composable
fun TrackMenuItems(song: Song?, place: TrackMenuPlace, menu: SongMenu, close: () -> Unit, openedUri: String? = null) {
    var state by remember(song?.id, openedUri) { mutableStateOf(TrackMenuState(inLibrary = song != null)) }
    LaunchedEffect(song?.id) {
        if (song != null) {
            state = TrackMenuState(
                links = menu.links(song),
                favorite = menu.favorites?.isFavorite?.invoke(song) == true,
                ownCover = menu.covers?.songHasOwn?.invoke(song) == true,
            )
        }
    }
    val identify = LocalIdentify.current
    val offers = TrackMenuOffers(
        addToPlaylist = menu.addToPlaylist != null,
        favorites = menu.favorites != null,
        covers = menu.covers != null,
        details = if (song != null) menu.details != null else openedUri != null && menu.openedDetails != null,
        identify = identify != null && (song != null || openedUri != null),
    )
    for (entry in TrackMenu.entries(place, offers, state)) {
        DropdownMenuItem(text = { Text(label(entry)) }, onClick = {
            close()
            when {
                entry == TrackMenuEntry.Identify -> (song?.let { IdentifyTarget.Song(it.id) } ?: openedUri?.let(IdentifyTarget::Opened))
                    ?.let { identify?.invoke(it) }
                song != null -> menu.run(entry, song)
                entry == TrackMenuEntry.Details && openedUri != null -> menu.openedDetails?.invoke(openedUri)
            }
        })
    }
}

@Composable
internal fun label(entry: TrackMenuEntry): String = when (entry) {
    TrackMenuEntry.PlayNext -> stringResource(R.string.play_next)
    TrackMenuEntry.AddToQueue -> stringResource(R.string.add_to_queue)
    TrackMenuEntry.AddToPlaylist -> stringResource(R.string.add_to_playlist)
    is TrackMenuEntry.GoToAlbum -> stringResource(R.string.go_to_album)
    is TrackMenuEntry.GoToArtist -> stringResource(R.string.go_to_artist, entry.artist.name)
    is TrackMenuEntry.GoToFolder -> stringResource(R.string.go_to_folder)
    is TrackMenuEntry.Favorite -> stringResource(if (entry.favorite) R.string.favorite_remove else R.string.favorite_add)
    TrackMenuEntry.ChooseCover -> stringResource(R.string.cover_choose)
    TrackMenuEntry.RemoveCover -> stringResource(R.string.cover_remove)
    TrackMenuEntry.Identify -> stringResource(R.string.identify)
    TrackMenuEntry.Details -> stringResource(R.string.details)
    TrackMenuEntry.Play -> stringResource(R.string.playlist_play)
    is TrackMenuEntry.FavoriteUnavailable ->
        stringResource(if (entry.reading) R.string.favorite_add else R.string.selection_favorites_not_read)
    TrackMenuEntry.RemoveFromPlaylist -> stringResource(R.string.playlist_remove)
    TrackMenuEntry.RemoveFromQueue -> stringResource(R.string.queue_remove)
}

/** Carries out [entry] for [song]. */
private fun SongMenu.run(entry: TrackMenuEntry, song: Song) {
    when (entry) {
        TrackMenuEntry.PlayNext -> playNext(song)
        TrackMenuEntry.AddToQueue -> addToQueue(song)
        TrackMenuEntry.AddToPlaylist -> addToPlaylist?.invoke(song)
        is TrackMenuEntry.GoToAlbum -> openAlbum(entry.key)
        is TrackMenuEntry.GoToArtist -> openArtist(entry.artist.key)
        is TrackMenuEntry.GoToFolder -> openFolder(entry.folder.volume, entry.folder.relativePath)
        is TrackMenuEntry.Favorite -> favorites?.set?.invoke(song, !entry.favorite)
        TrackMenuEntry.ChooseCover -> covers?.chooseForSong?.invoke(song)
        TrackMenuEntry.RemoveCover -> covers?.removeForSong?.invoke(song)
        // Opened through LocalIdentify by the menu itself.
        TrackMenuEntry.Identify -> Unit
        TrackMenuEntry.Details -> details?.invoke(song)
        // A selection's entries, which a song's own menu never has.
        TrackMenuEntry.Play, is TrackMenuEntry.FavoriteUnavailable, TrackMenuEntry.RemoveFromPlaylist, TrackMenuEntry.RemoveFromQueue -> Unit
    }
}
