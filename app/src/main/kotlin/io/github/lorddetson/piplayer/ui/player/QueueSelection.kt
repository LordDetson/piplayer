package io.github.lorddetson.piplayer.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import io.github.lorddetson.piplayer.ui.selection.QueueSelectionKeys
import io.github.lorddetson.piplayer.ui.selection.SavedSelections
import io.github.lorddetson.piplayer.ui.selection.SelectionHolder
import io.github.lorddetson.piplayer.ui.songs.SelectionFavorites

/**
 * The queue screen's selection (docs/library.md §20, docs/playback.md §9.6), by the screen's keys of its entries: it is
 * judged against the queue only once the saved queue is restored, and loses the entries whose media ID the queue holds
 * more than once whenever the queue changes.
 */
class QueueSelectionViewModel(saved: SavedStateHandle) : ViewModel() {

    val holder = SelectionHolder(SavedSelections.readKeys(saved)) { SavedSelections.writeKeys(saved, it) }

    /** The entries last judged against, as keys with their media IDs. */
    private var last: List<Pair<String, String>> = emptyList()

    /** The queue shows [entries]; [restored] is whether the saved queue's restore has ended. */
    fun onQueue(entries: List<QueueEntry>, restored: Boolean) {
        if (!restored) return
        val now = entries.map { it.key to it.mediaId }
        val before = last
        last = now
        holder.adjust { QueueSelectionKeys.afterChange(it, before, now) }
        holder.onList(now.map { it.first }, "")
    }
}

/** What the queue screen's selection menu does (docs/playback.md §9.6), besides the row menu's details. */
class QueueSelectionActions(
    val addToQueue: (QueueTarget, next: Boolean) -> Unit,
    val removeAll: (List<QueueEntry>) -> Unit,
    val addToPlaylist: ((QueueTarget, onChosen: () -> Unit) -> Unit)? = null,
    val favorites: SelectionFavorites? = null,
)
