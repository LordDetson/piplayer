package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.customtabs.CustomTabRecord
import io.github.lorddetson.piplayer.library.customtabs.CustomTabTemplateSource
import io.github.lorddetson.piplayer.library.customtabs.CustomTabTracks
import io.github.lorddetson.piplayer.library.customtabs.watchCustomTab
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.lists.SongSort
import io.github.lorddetson.piplayer.settings.ListOrder
import io.github.lorddetson.piplayer.ui.songs.AudioAccess
import io.github.lorddetson.piplayer.ui.songs.SongsContent
import io.github.lorddetson.piplayer.ui.songs.SongsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

@Composable
internal fun CustomTabContent(
    record: CustomTabRecord,
    order: ListOrder,
    search: String,
    access: AudioAccess,
    host: LibraryHost,
    modifier: Modifier,
    onRebind: () -> Unit,
) {
    if (!access.granted) {
        SongsContent(SongsState.NeedsPermission(access.deniedForGood), access.request, access.openSettings, { _, _ -> }, modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    val tracks = remember(context, record, order, search, attempt) {
        flow {
            if (search.isNotBlank()) delay(250)
            emitAll(watchCustomTab(context, record, order, search))
        }
    }
    val current = key(tracks) { tracks.collectAsStateWithLifecycle(initialValue = CustomTabTracks.Loading).value }

    when (val state = current) {
        CustomTabTracks.Loading -> Loading(modifier.testTag("custom_tab_loading"))
        CustomTabTracks.Failed -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.custom_tab_read_failed))
            Button(onClick = { attempt++ }) { Text(stringResource(R.string.custom_tab_retry)) }
        }
        CustomTabTracks.Unbound, CustomTabTracks.Missing ->
            MissingSource(record.definition.source, state == CustomTabTracks.Unbound, modifier, onRebind)
        is CustomTabTracks.Loaded -> {
            if (state.tracks.isEmpty()) {
                Empty(
                    stringResource(if (search.isBlank()) R.string.custom_tab_empty else R.string.search_nothing_found),
                    modifier.testTag("custom_tab_empty"),
                )
            } else {
                val songs = remember(state) { state.tracks.map { it.song } }
                val query = SongQuery.SONGS.copy(
                    sort = SongSort.of(order.field) ?: SongSort.TITLE,
                    reversed = order.reversed,
                    search = search,
                )
                SongsContent(
                    state = SongsState.Loaded(songs, query),
                    onAllowAccess = access.request,
                    onOpenSettings = access.openSettings,
                    onSongClick = { row, _ -> host.playSnapshot(songs.map { it.id }, row) },
                    modifier = modifier,
                    currentSongId = host.currentSongId,
                    menu = host.menu,
                    listTag = "custom_tab_songs",
                    songKey = { row, _ -> state.tracks[row].occurrence },
                )
            }
        }
    }
}

@Composable
private fun MissingSource(source: CustomTabTemplateSource, unbound: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    val message = when (source) {
        is CustomTabTemplateSource.Playlist ->
            if (unbound) stringResource(R.string.custom_tab_unbound_playlist, source.hint)
            else stringResource(R.string.custom_tab_missing_playlist)
        is CustomTabTemplateSource.Folder ->
            if (unbound) stringResource(R.string.custom_tab_unbound_folder, source.hint)
            else stringResource(R.string.custom_tab_missing_folder)
        else -> return
    }
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("custom_tab_missing"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message)
        Button(onClick = onChoose, modifier = Modifier.testTag("custom_tab_rebind")) {
            Text(stringResource(R.string.custom_tab_choose))
        }
    }
}
