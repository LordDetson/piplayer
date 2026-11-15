package io.github.lorddetson.piplayer.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.lorddetson.piplayer.ui.selection.SelectionBack
import io.github.lorddetson.piplayer.ui.selection.SelectionBar
import io.github.lorddetson.piplayer.ui.selection.SelectionMenuItems
import io.github.lorddetson.piplayer.ui.selection.SelectionUi
import io.github.lorddetson.piplayer.ui.songs.FavoriteBatch
import io.github.lorddetson.piplayer.ui.songs.SelectionMenuState
import io.github.lorddetson.piplayer.ui.songs.SelectionPlace
import io.github.lorddetson.piplayer.ui.songs.SongsViewModel
import io.github.lorddetson.piplayer.ui.songs.TrackMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry
import io.github.lorddetson.piplayer.ui.songs.TrackMenuOffers

/**
 * The selection bar of a library song list (docs/library.md §20): it takes the top bar's place while [viewModel] selects,
 * back ends the selection first, and its menu acts on the selected songs in the list's order.
 */
@Composable
internal fun SongsSelectionBar(host: LibraryHost, viewModel: SongsViewModel, state: SelectionUi<Long>) {
    SelectionBack(selecting = true, onEnd = viewModel::endSelection)
    SelectionBar(state, onClose = viewModel::endSelection, onSelectAll = viewModel::selectAll, onDeselectAll = viewModel::endSelection) { close ->
        // Read when the menu opens, since a dropdown composes its entries only while it is open.
        val selected = remember(state) { viewModel.selected() }
        val songs = selected?.songs.orEmpty()
        var favorites by remember(state) { mutableStateOf<FavoriteBatch?>(null) }
        LaunchedEffect(state) { favorites = host.favoriteBatch?.state?.invoke(songs.map { it.id }) }
        val offers = TrackMenuOffers(
            addToPlaylist = host.playlists != null,
            favorites = host.favoriteBatch != null,
            covers = false,
            details = host.menu.details != null,
        )
        val entries = TrackMenu.selectionEntries(SelectionPlace.LIST, offers, SelectionMenuState(songs.size, songs.size, favorites))
        SelectionMenuItems(entries, close) { entry ->
            val target = selected?.target ?: return@SelectionMenuItems
            when (entry) {
                TrackMenuEntry.Play -> host.playSelection(target)
                TrackMenuEntry.PlayNext -> host.addToQueue(target, true)
                TrackMenuEntry.AddToQueue -> host.addToQueue(target, false)
                // A cancelled chooser keeps the selection (§20.3).
                TrackMenuEntry.AddToPlaylist -> return@SelectionMenuItems host.playlists?.addSelected?.invoke(target, viewModel::endSelection) ?: Unit
                is TrackMenuEntry.Favorite -> host.favoriteBatch?.set?.invoke(songs.map { it.id }, !entry.favorite)
                TrackMenuEntry.Details -> songs.singleOrNull()?.let { host.menu.details?.invoke(it) }
                else -> Unit
            }
            viewModel.endSelection()
        }
    }
}
