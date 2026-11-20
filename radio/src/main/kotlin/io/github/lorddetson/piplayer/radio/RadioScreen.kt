package io.github.lorddetson.piplayer.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R as AppR
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadioScreen(
    directory: RadioBrowserClient,
    saved: SavedRadioStations,
    playStation: suspend (name: String, url: String) -> Boolean,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tabState = rememberSaveableStateHolder()
    var section by rememberSaveable { mutableStateOf(RadioSection.DIRECTORY) }
    var results by remember { mutableStateOf<RadioCatalogResults?>(null) }
    var failedQuery by remember { mutableStateOf<RadioStationQuery?>(null) }
    var searching by remember { mutableStateOf(false) }
    var playingRequest by remember { mutableStateOf(false) }
    var savedStations by remember { mutableStateOf<List<SavedRadioStation>?>(null) }
    var savedReadFailed by remember { mutableStateOf(false) }
    var readAttempt by remember { mutableIntStateOf(0) }
    var writing by remember { mutableStateOf(false) }
    var addStation by rememberSaveable { mutableStateOf(false) }
    var addError by remember { mutableStateOf<SavedStationWrite?>(null) }
    var removeStation by remember { mutableStateOf<SavedRadioStation?>(null) }

    LaunchedEffect(saved, readAttempt) {
        savedReadFailed = false
        try {
            saved.stations.collect { savedStations = it }
        } catch (_: IOException) {
            savedReadFailed = true
        }
    }

    fun requestPage(query: RadioStationQuery, newSearch: Boolean = false) {
        if (searching) return
        if (newSearch) results = RadioCatalogResults(query)
        searching = true
        failedQuery = null
        scope.launch {
            try {
                val page = directory.search(query)
                results = results?.append(page)
            } catch (_: IOException) {
                failedQuery = query
            } finally {
                searching = false
            }
        }
    }

    fun play(name: String, url: HttpUrl) {
        if (playingRequest) return
        playingRequest = true
        scope.launch {
            val accepted = try {
                playStation(name, url.toString())
            } finally {
                playingRequest = false
            }
            if (!accepted) snackbar.showSnackbar(resources.getString(R.string.radio_playback_error))
        }
    }

    fun write(
        operation: suspend () -> SavedStationWrite,
        onResult: (SavedStationWrite) -> Unit = { result ->
            if (result != SavedStationWrite.SAVED) {
                scope.launch { snackbar.showSnackbar(resources.getString(result.message)) }
            }
        },
    ) {
        if (writing) return
        writing = true
        scope.launch {
            val result = try {
                operation()
            } finally {
                writing = false
            }
            onResult(result)
        }
    }

    val stored = savedStations.orEmpty()
    val directoryFavorites = remember(stored) {
        stored.filter { it.favorite && it.id.source == RadioStationSource.DIRECTORY }.associateBy { it.id.uuid }
    }
    val canWrite = savedStations != null && !savedReadFailed && !writing
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("radio-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.radio_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(AppR.drawable.ic_back), stringResource(R.string.radio_back))
                    }
                },
            )
        },
        bottomBar = { RadioCurrentStream() },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = section.ordinal) {
                for (tab in RadioSection.entries) {
                    Tab(
                        selected = section == tab,
                        onClick = { section = tab },
                        text = { Text(stringResource(tab.label)) },
                        modifier = Modifier.testTag(tab.testTag),
                    )
                }
            }
            tabState.SaveableStateProvider(section.name) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (section == RadioSection.DIRECTORY) {
                        item { RadioSearchForm(searching) { requestPage(it, newSearch = true) } }
                        if (searching) item {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        }
                        val failure = failedQuery
                        if (failure != null) item {
                            RadioNotice(R.string.radio_search_error, R.string.radio_retry) { requestPage(failure) }
                        }
                        val page = results
                        if (page != null) {
                            if (page.stations.isEmpty() && !searching && failure == null) item {
                                RadioNotice(R.string.radio_no_results)
                            }
                            items(page.stations, key = { it.id.toString() }) { station ->
                                StationRow(
                                    id = station.id,
                                    name = station.name,
                                    description = (listOf(station.countryCode) + station.languages + station.tags)
                                        .filter(String::isNotBlank).distinct().joinToString(" · "),
                                    favorite = station.id in directoryFavorites,
                                    canPlay = !playingRequest,
                                    canWrite = canWrite,
                                    onPlay = { play(station.name, station.streamUrl) },
                                    onFavorite = { write({ saved.setDirectoryFavorite(station, station.id !in directoryFavorites) }) },
                                )
                            }
                            val next = page.nextQuery()
                            if (next != null && !searching && failure == null) item {
                                TextButton(onClick = { requestPage(next) }, modifier = Modifier.testTag("radio-load-more")) {
                                    Text(stringResource(R.string.radio_load_more))
                                }
                            }
                        }
                    } else {
                        if (section == RadioSection.MY_STATIONS) item {
                            TextButton(
                                enabled = canWrite,
                                onClick = { addStation = true },
                                modifier = Modifier.padding(horizontal = 8.dp).testTag("radio-add"),
                            ) { Text(stringResource(R.string.radio_add)) }
                        }
                        val visible = section.savedStations(stored)
                        when {
                            savedReadFailed -> item {
                                RadioNotice(R.string.radio_storage_error, R.string.radio_retry) { readAttempt++ }
                            }
                            savedStations == null -> item {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            }
                            visible.isEmpty() -> item {
                                RadioNotice(if (section == RadioSection.FAVORITES) R.string.radio_no_favorites else R.string.radio_no_custom)
                            }
                        }
                        items(visible, key = { "${it.id.source}-${it.id.uuid}" }) { station ->
                            val custom = station.id.source == RadioStationSource.CUSTOM
                            StationRow(
                                id = station.id.uuid,
                                name = station.name,
                                description = station.streamUrl.host,
                                favorite = station.favorite,
                                canPlay = !playingRequest,
                                canWrite = canWrite,
                                onPlay = { play(station.name, station.streamUrl) },
                                onFavorite = {
                                    write({
                                        if (custom) saved.setCustomFavorite(station.id.uuid, !station.favorite)
                                        else saved.removeDirectoryFavorite(station.id.uuid)
                                    })
                                },
                                onRemove = if (custom) ({ removeStation = station }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    if (addStation) {
        fun closeAdd() {
            addError = null
            addStation = false
        }
        RadioAddStationDialog(
            writing = writing,
            error = addError,
            onDismiss = ::closeAdd,
            onSave = { name, url ->
                write({ saved.addCustom(name, url) }) { result ->
                    if (result == SavedStationWrite.SAVED) closeAdd() else addError = result
                }
            },
        )
    }
    removeStation?.let { station ->
        AlertDialog(
            onDismissRequest = { removeStation = null },
            title = { Text(stringResource(R.string.radio_remove_station)) },
            text = { Text(station.name) },
            confirmButton = {
                TextButton(onClick = {
                    removeStation = null
                    write({ saved.removeCustom(station.id.uuid) })
                }) { Text(stringResource(R.string.radio_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeStation = null }) { Text(stringResource(R.string.radio_cancel)) }
            },
        )
    }
}

@Composable
private fun StationRow(
    id: UUID,
    name: String,
    description: String,
    favorite: Boolean,
    canPlay: Boolean,
    canWrite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.testTag("radio-station-$id").clickable(
            enabled = canPlay,
            onClickLabel = stringResource(R.string.radio_play_station, name),
            onClick = onPlay,
        ),
        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (description.isBlank()) null else ({
            Text(description, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }),
        leadingContent = { Icon(painterResource(AppR.drawable.ic_player_play), contentDescription = null) },
        trailingContent = {
            Row {
                IconButton(onClick = onFavorite, enabled = canWrite, modifier = Modifier.testTag("radio-favorite-$id")) {
                    Icon(
                        painterResource(if (favorite) AppR.drawable.ic_favorite else AppR.drawable.ic_favorite_border),
                        stringResource(if (favorite) R.string.radio_unfavorite else R.string.radio_favorite, name),
                    )
                }
                if (onRemove != null) IconButton(onClick = onRemove, enabled = canWrite) {
                    Icon(painterResource(AppR.drawable.ic_close), stringResource(R.string.radio_remove_named, name))
                }
            }
        },
    )
}

@Composable
private fun RadioNotice(message: Int, action: Int? = null, onClick: () -> Unit = {}) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onClick) { Text(stringResource(action)) }
    }
}

private val RadioSection.label: Int
    get() = when (this) {
        RadioSection.DIRECTORY -> R.string.radio_directory
        RadioSection.FAVORITES -> R.string.radio_favorites
        RadioSection.MY_STATIONS -> R.string.radio_custom
    }

private val RadioSection.testTag: String
    get() = when (this) {
        RadioSection.DIRECTORY -> "radio-section-directory"
        RadioSection.FAVORITES -> "radio-section-favorites"
        RadioSection.MY_STATIONS -> "radio-section-my-stations"
    }

internal val SavedStationWrite.message: Int
    get() = when (this) {
        SavedStationWrite.INVALID_URL -> R.string.radio_invalid_url
        SavedStationWrite.FULL -> R.string.radio_storage_full
        SavedStationWrite.NOT_FOUND -> R.string.radio_station_missing
        SavedStationWrite.WRITE_FAILED -> R.string.radio_storage_write_error
        SavedStationWrite.SAVED -> error("A saved station has no error message")
    }
