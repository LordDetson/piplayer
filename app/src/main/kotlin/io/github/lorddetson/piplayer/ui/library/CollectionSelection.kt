package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import io.github.lorddetson.piplayer.ui.selection.RowSelection
import io.github.lorddetson.piplayer.ui.selection.SelectionCheck
import io.github.lorddetson.piplayer.ui.selection.SelectionBack
import io.github.lorddetson.piplayer.ui.selection.SelectionBar
import io.github.lorddetson.piplayer.ui.selection.SelectionHolder
import io.github.lorddetson.piplayer.ui.selection.SelectionMenuItems
import io.github.lorddetson.piplayer.ui.selection.SelectionUi
import io.github.lorddetson.piplayer.ui.selection.isSelected
import io.github.lorddetson.piplayer.ui.selection.ordered
import io.github.lorddetson.piplayer.ui.selection.selectableRow
import io.github.lorddetson.piplayer.ui.songs.TrackMenuEntry

/** Selection belongs to the visible collection list. Its queue follows that list's order. */
internal class CollectionSelection(
    private val holder: SelectionHolder<QueueTarget.Collection>,
    private val state: SelectionUi<QueueTarget.Collection>?,
    private val shown: List<QueueTarget.Collection>,
    private val onStart: () -> Unit,
) {
    val selecting: Boolean get() = state != null

    fun row(target: QueueTarget.Collection) = RowSelection(selecting, state?.selection?.isSelected(target) == true) {
        if (!holder.selecting) onStart()
        holder.toggle(target)
    }

    fun end() = holder.end()

    @Composable
    fun Actions(host: LibraryHost) {
        SelectionBack(selecting, ::end)
        val selected = state ?: return
        SelectionBar(selected, ::end, holder::selectAll, ::end) { close ->
            SelectionMenuItems(listOf(TrackMenuEntry.AddToQueue, TrackMenuEntry.Play), close) { action ->
                val targets = selected.selection.ordered(shown)
                if (targets.isNotEmpty()) {
                    val target = QueueTarget.Collections(targets)
                    if (action == TrackMenuEntry.Play) host.playSelection(target) else host.addToQueue(target, false)
                    end()
                }
            }
        }
    }
}

@Composable
internal fun rememberCollectionSelection(
    shown: List<QueueTarget.Collection>,
    search: String,
    onStart: () -> Unit = {},
): CollectionSelection {
    val holder = remember { SelectionHolder<QueueTarget.Collection>(null) }
    val selected by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(shown, search) { holder.onList(shown, search) }
    return CollectionSelection(holder, selected, shown, onStart)
}

@Composable
internal fun CollectionTile(
    title: String,
    details: String?,
    icon: Int,
    clickLabel: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    row: RowSelection? = null,
    menu: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape).selectableRow(row, clickLabel, onOpen).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, shape).padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row?.selecting == true) Box(Modifier.align(Alignment.TopEnd)) { SelectionCheck(row) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (row?.selecting != true) menu()
        }
    }
}
