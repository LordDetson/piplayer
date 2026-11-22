package io.github.lorddetson.piplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.BottomDestination
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.ui.customization.LocalUiCatalog
import io.github.lorddetson.piplayer.ui.customization.UiCanvasComponent
import io.github.lorddetson.piplayer.ui.customization.UiControlIcon
import io.github.lorddetson.piplayer.ui.library.AlbumPage
import io.github.lorddetson.piplayer.ui.player.FavoriteState
import io.github.lorddetson.piplayer.ui.songs.FavoriteActions
import io.github.lorddetson.piplayer.ui.songs.SelectionFavorites
import io.github.lorddetson.piplayer.ui.library.ArtistPage
import io.github.lorddetson.piplayer.ui.library.FolderPage
import io.github.lorddetson.piplayer.ui.library.GenrePage
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.library.LibraryScreen
import io.github.lorddetson.piplayer.ui.library.LibraryWorkspaceChrome
import io.github.lorddetson.piplayer.ui.fix.BatchActions
import io.github.lorddetson.piplayer.ui.fix.FixScreen
import io.github.lorddetson.piplayer.ui.fix.BatchTagWriteScreen
import io.github.lorddetson.piplayer.ui.fix.rememberBatchActions
import io.github.lorddetson.piplayer.ui.notifications.BatchNotificationExplanation
import io.github.lorddetson.piplayer.ui.notifications.rememberBatchNotificationPrompt
import io.github.lorddetson.piplayer.ui.search.SearchScreen
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.ui.playlists.PlaylistPage
import io.github.lorddetson.piplayer.ui.playlists.SmartListPage
import io.github.lorddetson.piplayer.ui.playlists.rememberPlaylistActions
import io.github.lorddetson.piplayer.ui.onboarding.OnboardingGate
import io.github.lorddetson.piplayer.ui.demo.DemoScreen
import io.github.lorddetson.piplayer.ui.onboarding.rememberOnboarding
import io.github.lorddetson.piplayer.ui.settings.LicenseTextScreen
import io.github.lorddetson.piplayer.ui.settings.LicensesScreen
import io.github.lorddetson.piplayer.ui.songs.SongMenu
import io.github.lorddetson.piplayer.ui.songs.TrackMenuItems
import io.github.lorddetson.piplayer.ui.songs.TrackMenuPlace
import io.github.lorddetson.piplayer.ui.details.TrackDetailsScreen
import io.github.lorddetson.piplayer.ui.details.ManualTagEditorScreen
import io.github.lorddetson.piplayer.ui.details.TagHistoryScreen
import io.github.lorddetson.piplayer.ui.identification.IdentifyTarget
import io.github.lorddetson.piplayer.ui.identification.LocalIdentify
import io.github.lorddetson.piplayer.ui.details.BadgeHost
import io.github.lorddetson.piplayer.ui.details.LocalBadgeHost
import io.github.lorddetson.piplayer.ui.details.MetadataBadges
import io.github.lorddetson.piplayer.ui.identification.ReviewScreen
import androidx.compose.runtime.CompositionLocalProvider
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.covers.CoverChoices
import io.github.lorddetson.piplayer.ui.covers.CoverActions
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import io.github.lorddetson.piplayer.playback.QueueIds
import io.github.lorddetson.piplayer.ui.player.QueueActions
import io.github.lorddetson.piplayer.ui.player.QueueSelectionActions
import io.github.lorddetson.piplayer.ui.player.QueueScreen
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.ui.crash.CrashPrompt
import io.github.lorddetson.piplayer.ui.player.EqualizerPanel
import io.github.lorddetson.piplayer.ui.player.EqualizerButton
import io.github.lorddetson.piplayer.ui.player.ExtraControls
import io.github.lorddetson.piplayer.ui.player.MiniPlayer
import io.github.lorddetson.piplayer.ui.player.NextButton
import io.github.lorddetson.piplayer.ui.player.NowPlaying
import io.github.lorddetson.piplayer.ui.player.PlayPauseButton
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.player.PreviousButton
import io.github.lorddetson.piplayer.ui.player.RepeatButton
import io.github.lorddetson.piplayer.ui.player.ShuffleButton
import io.github.lorddetson.piplayer.ui.player.SleepTimerButton
import io.github.lorddetson.piplayer.ui.player.StopAfterCurrentButton
import io.github.lorddetson.piplayer.ui.settings.FoldersScreen
import io.github.lorddetson.piplayer.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** The library tabs with the mini player (docs/library.md §6.1). */
@Serializable
data object SongsRoute : NavKey

/** The Search place of the bottom bar: the library's song search as a screen of its own (docs/library.md §6.1, PIM-18). */
@Serializable
data object SearchRoute : NavKey

/** The Fix place of the bottom bar: the batch queue of identification (TI §8, PIM-18). */
@Serializable
data object FixRoute : NavKey

/** An album page, found by its album key (docs/library.md §6.5). */
@Serializable
data class AlbumRoute(val key: String) : NavKey

/** An artist page, found by its artist key. */
@Serializable
data class ArtistRoute(val key: String) : NavKey

/** A folder page, found by its volume and relative path. */
@Serializable
data class LibraryFolderRoute(val volume: String, val relativePath: String) : NavKey

/** A playlist's page, found by its ID (docs/library.md §16.3). */
@Serializable
data class PlaylistRoute(val id: Long) : NavKey

/** A genre page, found by its genre key, `!none` for the songs without one (docs/library.md §19.4). */
@Serializable
data class GenreRoute(val key: String) : NavKey

/** A smart list's page, found by the token of its list kind (docs/library.md §17.2). */
@Serializable
data class SmartListRoute(val kind: String) : NavKey

@Serializable
data object NowPlayingRoute : NavKey

/** The queue screen (docs/playback.md §9.2), opened from Now Playing. */
@Serializable
data object QueueRoute : NavKey

/**
 * The details of a song of the library by its file ID, or of a file opened from another app by its URI (docs/library.md
 * §18.1).
 */
@Serializable
data class TrackDetailsRoute(val fileId: Long? = null, val uri: String? = null) : NavKey

@Serializable
data class TagHistoryRoute(val identityId: Long) : NavKey

@Serializable
data class ManualTagEditRoute(val fileId: Long) : NavKey

/**
 * The review of a song of the library by its file ID, or of a file opened from another app by its URI (TI §6, review
 * screen A). [batchUuid] is the batch whose card opened it (TI §8), null for a single-file review.
 */
@Serializable
data class IdentifyRoute(val fileId: Long? = null, val uri: String? = null, val batchUuid: String? = null) : NavKey

@Serializable
data class BatchTagWriteRoute(val batchUuid: String) : NavKey

/** The Library folders screen (docs/library.md §3.6). */
@Serializable
data object FoldersRoute : NavKey

/** The Settings screen (docs/library.md §3.6). */
@Serializable
data object SettingsRoute : NavKey

@Serializable
data object DemoRoute : NavKey

/** The open-source licences (docs/library.md §14.3). */
@Serializable
data object LicensesRoute : NavKey

/** One licence: a module's [notice] if it has one, then the [texts] (asset paths) one after another. */
@Serializable
data class LicenseTextRoute(val title: String, val notice: String?, val texts: List<String>) : NavKey

// Settings pages come in from the side, unlike Now Playing.
private val FROM_THE_SIDE = NavDisplay.transitionSpec {
    slideInHorizontally(initialOffsetX = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished
} + NavDisplay.popTransitionSpec {
    EnterTransition.None togetherWith slideOutHorizontally(targetOffsetX = { it })
} + NavDisplay.predictivePopTransitionSpec {
    EnterTransition.None togetherWith slideOutHorizontally(targetOffsetX = { it })
}

/**
 * The app's screens (docs/playback.md §3.1): the song list and Now Playing over it, which slides up when it opens and
 * down when it closes, following the predictive back gesture. Back is PiPlayer's only while Now Playing is open. The
 * crash dialog (§7.3) comes over either. A first launch without access shows onboarding in place of the library
 * (docs/library.md §14.1).
 */
@Composable
fun PiPlayerApp(
    viewModel: PlayerViewModel = viewModel(),
    nowPlayingLayout: NowPlayingLayout = NowPlayingLayout.STANDARD,
    backStack: MutableList<NavKey> = rememberNavBackStack(SongsRoute),
) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val coverName by viewModel.coverName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        // Messages come one after another; one with "Undo" gives way to the next at once, since a newer one may come
        // from a change of the queue that its undo no longer fits (docs/playback.md §9.2).
        var shown: Job? = null
        var undoable = false
        viewModel.messages.collect { message ->
            if (undoable) shown?.cancel() else shown?.join()
            undoable = message.action != null
            shown = launch {
                val result = snackbar.showSnackbar(message.text, message.actionLabel, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
            }
        }
    }
    val onboarding = rememberOnboarding()
    val close = { if (backStack.size > 1) backStack.removeLastOrNull() }
    // A page opens over the library or the page it was opened from; Now Playing gives way to it.
    val open = { route: NavKey ->
        if (backStack.lastOrNull() == NowPlayingRoute) backStack.removeLastOrNull()
        if (backStack.lastOrNull() != route) backStack.add(route)
    }
    // An item of the bottom bar makes its place the base of the stack: the pages pushed over the place pop first, and
    // the tapped place again takes back to its root (PIM-18, TI §8).
    val switchTo = { route: NavKey ->
        while (backStack.size > 1) backStack.removeLastOrNull()
        if (backStack.firstOrNull() != route) backStack.set(0, route)
    }
    // Details open over whatever shows, Now Playing included, so that back returns there (docs/library.md §18.1).
    val showDetails = { route: TrackDetailsRoute -> if (backStack.lastOrNull() != route) backStack.add(route) }
    // A batch's notification asks for the Fix tab (TI §8, PIM-21): the place becomes the base of the stack once.
    val requestedPlace by Places.requested.collectAsStateWithLifecycle()
    LaunchedEffect(requestedPlace) {
        if (requestedPlace == Places.FIX && Places.take(Places.FIX)) switchTo(FixRoute)
    }
    // The review opens over whatever shows too, Now Playing and details included (TI §1).
    val identify = remember {
        { target: IdentifyTarget ->
            val route = when (target) {
                is IdentifyTarget.Song -> IdentifyRoute(fileId = target.fileId)
                is IdentifyTarget.Opened -> IdentifyRoute(uri = target.uri)
            }
            if (backStack.lastOrNull() != route) backStack.add(route)
        }
    }
    // Now Playing's metadata badge reads the library and opens a song's details (docs/library.md §18.7).
    val appContext = LocalContext.current.applicationContext
    val badges = remember { BadgeHost({ MetadataBadges.of(appContext, it) }, { showDetails(TrackDetailsRoute(fileId = it)) }) }
    // Every entry reaches "Identify" through LocalIdentify (TI §1), and the badge through LocalBadgeHost.
    val identifyDecorator = remember {
        NavEntryDecorator<NavKey> { entry -> CompositionLocalProvider(LocalIdentify provides identify, LocalBadgeHost provides badges) { entry.Content() } }
    }
    val context = LocalContext.current
    val currentMediaId = rememberCurrentMediaId(player)
    // An added entry plays a library song too (docs/playback.md §9.4).
    val currentSongId = currentMediaId?.let(QueueIds::fileId)
    val covers = rememberCoverActions(snackbar)
    val favorites = remember(context) { FavoriteChoices(context) }
    val favoriteActions = rememberFavoriteActions(favorites, snackbar)
    val resources = LocalResources.current
    val messageScope = rememberCoroutineScope()
    val favoriteBatch = remember(favoriteActions) {
        favoriteActions.batch({ text -> messageScope.launch { snackbar.showSnackbar(text) } }, resources)
    }
    val playlists = rememberPlaylistActions(
        snackbar,
        open = { id -> open(PlaylistRoute(id)) },
        openSmart = { kind -> open(SmartListRoute(kind.token)) },
    )
    val batch = rememberBatchActions(snackbar)
    // The explanation of the notification permission comes before the first batch (TI §8, PIM-21); the batch runs the
    // same without it.
    val batchNotifications = rememberBatchNotificationPrompt()
    val batchAsked = remember(batch, batchNotifications) {
        BatchActions { request -> batchNotifications.beforeBatch { batch.identify(request) } }
    }
    val menu = remember {
        SongMenu(
            links = { song -> linksOf(context, song.id) },
            openAlbum = { open(AlbumRoute(it)) },
            openArtist = { open(ArtistRoute(it)) },
            openFolder = { volume, path -> open(LibraryFolderRoute(volume, path)) },
            playNext = { viewModel.addToQueue(QueueTarget.Songs(listOf(it.id)), next = true) },
            addToQueue = { viewModel.addToQueue(QueueTarget.Songs(listOf(it.id)), next = false) },
            covers = covers,
            favorites = favoriteActions.actions,
            addToPlaylist = { playlists.addToPlaylist(QueueTarget.Songs(listOf(it.id))) },
            details = { showDetails(TrackDetailsRoute(fileId = it.id)) },
            openedDetails = { uri -> showDetails(TrackDetailsRoute(uri = uri)) },
            editTags = { open(ManualTagEditRoute(it.id)) },
        )
    }
    val host = LibraryHost(
        player = player,
        cover = cover,
        coverName = coverName,
        snackbar = snackbar,
        currentSongId = currentSongId,
        menu = menu,
        play = viewModel::play,
        shuffle = viewModel::shuffle,
        addToQueue = viewModel::addToQueue,
        openNowPlaying = { if (backStack.lastOrNull() != NowPlayingRoute) backStack.add(NowPlayingRoute) },
        openAlbum = menu.openAlbum,
        openArtist = menu.openArtist,
        openFolder = menu.openFolder,
        covers = covers,
        playlists = playlists,
        openGenre = { open(GenreRoute(it)) },
        playSelection = viewModel::playSelection,
        favoriteBatch = favoriteBatch,
        batch = batchAsked,
    )
    // What the artist and the album on Now Playing open (docs/library.md §6.1).
    var playingLinks by remember { mutableStateOf<SongLinks?>(null) }
    LaunchedEffect(currentSongId) {
        playingLinks = null
        playingLinks = currentSongId?.let { linksOf(context, it) }
    }
    val extraControls = rememberExtraControls(viewModel, player)
    val canvasCatalog = buildList {
        BottomDestination.entries.forEach { add(bottomNavigationComponent(it, backStack.firstOrNull(), switchTo)) }
        add(UiCanvasComponent("library:settings", R.string.settings_open, customizableIcon = true) {
            IconButton(onClick = { open(SettingsRoute) }) {
                UiControlIcon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_open))
            }
        })
        add(UiCanvasComponent("now:queue", R.string.queue_open, customizableIcon = true) {
            IconButton(onClick = { open(QueueRoute) }) {
                UiControlIcon(painterResource(R.drawable.ic_queue), stringResource(R.string.queue_open))
            }
        })
        add(UiCanvasComponent("now:previous", R.string.player_previous, customizableIcon = true) { PreviousButton(player) })
        add(UiCanvasComponent("now:play_pause", R.string.player_element_play_pause, customizableIcon = true) { PlayPauseButton(player) })
        add(UiCanvasComponent("now:next", R.string.player_next, customizableIcon = true) { NextButton(player) })
        add(UiCanvasComponent("now:shuffle", R.string.player_shuffle, customizableIcon = true) { ShuffleButton(player) })
        add(UiCanvasComponent("now:repeat", R.string.player_repeat, customizableIcon = true) { RepeatButton(player) })
        add(UiCanvasComponent("now:timer", R.string.player_sleep_timer, width = 96.dp, customizableIcon = true, alignableText = true) {
            SleepTimerButton(extraControls, Modifier)
        })
        add(UiCanvasComponent("now:stop_after", R.string.player_stop_after_current, customizableIcon = true) {
            StopAfterCurrentButton(extraControls, Modifier)
        })
        extraControls.openEqualizer?.let { equalizer ->
            add(UiCanvasComponent("now:equalizer", R.string.player_equalizer, customizableIcon = true) {
                EqualizerButton(equalizer, Modifier)
            })
        }
    }
    AppCanvasColumn(canvasCatalog) {
        NavDisplay(
            backStack = backStack,
            onBack = { close() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator(), identifyDecorator),
            transitionSpec = { slideInVertically(initialOffsetY = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished },
            popTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
            entryProvider = entryProvider {
                entry<SongsRoute> {
                    OnboardingGate(
                        onboarding,
                        snackbarHost = { SnackbarHost(host.snackbar) },
                        onTryDemo = { open(DemoRoute) },
                    ) {
                        LibraryScreen(
                            host,
                            chrome = LibraryWorkspaceChrome(
                                miniPlayer = {
                                    MiniPlayer(host.player, host.cover, onOpen = host.openNowPlaying, coverName = host.coverName,
                                        bottomInset = false, snackbar = host.snackbar)
                                },
                                navigation = { PiPlayerBottomBar(backStack.firstOrNull(), switchTo, snackbar) },
                            ),
                            onOpenSettings = { if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute) },
                        )
                    }
                }
                entry<SearchRoute> { SearchScreen(host) }
                entry<FixRoute> {
                    FixScreen(
                        onOpenReview = { fileId, batchUuid -> open(IdentifyRoute(fileId = fileId, batchUuid = batchUuid)) },
                        onOpenWrite = { batchUuid -> open(BatchTagWriteRoute(batchUuid)) },
                        onOpenHistory = { identityId -> open(TagHistoryRoute(identityId)) },
                        snackbar = host.snackbar,
                    )
                }
                entry<BatchTagWriteRoute>(metadata = FROM_THE_SIDE) { route ->
                    BatchTagWriteScreen(route.batchUuid, onBack = { close() })
                }
                entry<AlbumRoute>(metadata = FROM_THE_SIDE) { route -> AlbumPage(route.key, host, onBack = { close() }) }
                entry<ArtistRoute>(metadata = FROM_THE_SIDE) { route -> ArtistPage(route.key, host, onBack = { close() }) }
                entry<LibraryFolderRoute>(metadata = FROM_THE_SIDE) { route ->
                    FolderPage(route.volume, route.relativePath, host, onBack = { close() })
                }
                entry<PlaylistRoute>(metadata = FROM_THE_SIDE) { route -> PlaylistPage(route.id, host, playlists, onBack = { close() }) }
                entry<GenreRoute>(metadata = FROM_THE_SIDE) { route -> GenrePage(route.key, host, onBack = { close() }) }
                entry<SmartListRoute>(metadata = FROM_THE_SIDE) { route ->
                    ListKind.of(route.kind)?.takeIf { it.smart }?.let { SmartListPage(it, host, onBack = { close() }) }
                }
                entry<QueueRoute>(metadata = FROM_THE_SIDE) {
                    QueueScreen(
                        player = player,
                        actions = QueueActions(
                            remove = { entry -> viewModel.removeFromQueue(entry.row, entry.mediaId, entry.title) },
                            move = { entry, from, to -> viewModel.moveInQueue(from, to, entry.mediaId) },
                        ),
                        snackbar = snackbar,
                        onBack = { close() },
                        menu = menu,
                        selectionActions = QueueSelectionActions(
                            addToQueue = viewModel::addToQueue,
                            removeAll = viewModel::removeFromQueue,
                            addToPlaylist = playlists.addSelected,
                            favorites = favoriteBatch,
                        ),
                        restored = viewModel.restored.collectAsStateWithLifecycle().value,
                    )
                }
                entry<TrackDetailsRoute>(metadata = FROM_THE_SIDE) { route ->
                    TrackDetailsScreen(route.fileId, route.uri, snackbar, onBack = { close() }, onTagHistory = { open(TagHistoryRoute(it)) })
                }
                entry<TagHistoryRoute>(metadata = FROM_THE_SIDE) { route -> TagHistoryScreen(route.identityId, onBack = { close() }) }
                entry<ManualTagEditRoute>(metadata = FROM_THE_SIDE) { route -> ManualTagEditorScreen(route.fileId, onBack = { close() }) }
                entry<IdentifyRoute>(metadata = FROM_THE_SIDE) { route ->
                    ReviewScreen(route.fileId, route.uri, onBack = { close() }, batchUuid = route.batchUuid)
                }
                entry<SettingsRoute>(metadata = FROM_THE_SIDE) {
                    SettingsScreen(
                        onBack = { close() },
                        onOpenFolders = { if (backStack.lastOrNull() != FoldersRoute) backStack.add(FoldersRoute) },
                        onOpenLicenses = { if (backStack.lastOrNull() != LicensesRoute) backStack.add(LicensesRoute) },
                        onOpenDemo = { open(DemoRoute) },
                    )
                }
                entry<DemoRoute>(metadata = FROM_THE_SIDE) { DemoScreen(onBack = { close() }) }
                entry<LicensesRoute>(metadata = FROM_THE_SIDE) {
                    LicensesScreen(
                        onBack = { close() },
                        onOpen = { title, notice, texts ->
                            if (backStack.lastOrNull() !is LicenseTextRoute) backStack.add(LicenseTextRoute(title, notice, texts))
                        },
                    )
                }
                entry<LicenseTextRoute>(metadata = FROM_THE_SIDE) { route ->
                    LicenseTextScreen(route.title, route.notice, route.texts, onBack = { close() })
                }
                entry<FoldersRoute>(metadata = FROM_THE_SIDE) {
                    FoldersScreen(onBack = { close() })
                }
                entry<NowPlayingRoute> {
                    Box(Modifier.fillMaxSize()) {
                        val writer = (appContext as PiPlayerApplication).batchTagWriter
                        val waiting by writer.waitingFiles.collectAsStateWithLifecycle(initialValue = emptySet())
                        val pendingFileId = currentSongId?.takeIf { it in waiting }
                        val links = playingLinks
                        val scope = rememberCoroutineScope()
                        val panelSaveFailed = stringResource(R.string.player_panel_save_failed)
                        // The keys are read again at the tap: the tag pass may have changed them since the song began.
                        val openLatest = { route: (SongLinks) -> NavKey? ->
                            scope.launch {
                                val latest = currentSongId?.let { linksOf(context, it) }
                                latest?.let(route)?.let(open)
                            }
                            Unit
                        }
                        NowPlaying(
                            player,
                            cover,
                            onClose = { close() },
                            layout = nowPlayingLayout,
                            onPanelSaveFailed = { snackbar.showSnackbar(panelSaveFailed) },
                            controls = extraControls,
                            onOpenArtist = links?.artists?.firstOrNull()?.let {
                                { openLatest { latest -> latest.artists.firstOrNull()?.let { ArtistRoute(it.key) } } }
                            },
                            onOpenAlbum = links?.albumKey?.let { { openLatest { latest -> latest.albumKey?.let(::AlbumRoute) } } },
                            onOpenQueue = { if (backStack.lastOrNull() != QueueRoute) backStack.add(QueueRoute) },
                            coverName = coverName,
                            favorite = currentSongId?.let { id ->
                                val checked by remember(id) { favorites.favoriteFlow(id) }.collectAsStateWithLifecycle(false)
                                FavoriteState(checked) { favoriteActions.setById(id, !checked) }
                            },
                            more = currentMediaId?.let { mediaId ->
                                { closeMenu ->
                                    if (pendingFileId != null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.tag_write_now)) },
                                            onClick = {
                                                closeMenu()
                                                viewModel.writeTagsNow(mediaId, pendingFileId)
                                            },
                                        )
                                    }
                                    val song = currentSongId?.let { Song(id = it, uri = mediaId, title = "", artist = null, album = null, durationMs = null) }
                                    TrackMenuItems(song, TrackMenuPlace.NOW_PLAYING, menu, closeMenu, openedUri = mediaId.takeIf { song == null })
                                }
                            },
                        )
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            },
        )
        // The bottom bar of the three top-level places, the mini player above it (PIM-18, docs/playback.md §3.1).
        // Onboarding keeps the mini player for a file opened from another app, without the bar (docs/library.md §14.1).
        val current = backStack.lastOrNull()
        if (current in TOP_LEVEL_PLACES && (current != SongsRoute || onboarding.showing)) {
            MiniPlayer(
                host.player,
                host.cover,
                onOpen = host.openNowPlaying,
                coverName = host.coverName,
                bottomInset = onboarding.showing,
                snackbar = host.snackbar,
            )
            if (!onboarding.showing) PiPlayerBottomBar(backStack.firstOrNull(), switchTo, snackbar)
        }
    }
    if (CrashReports.promptEnabled) CrashPrompt(CrashReports.of(LocalContext.current))
    BatchNotificationExplanation(batchNotifications)
}

@Composable
private fun AppCanvasColumn(catalog: List<UiCanvasComponent>, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalUiCatalog provides catalog) {
        Column(Modifier.fillMaxSize(), content = content)
    }
}

/** The bottom bar's places, in their order (TI §8, PIM-18). */
private val TOP_LEVEL_PLACES = listOf(SongsRoute, SearchRoute, FixRoute)

/**
 * "Choose cover" and "Remove own cover" (docs/library.md §13.2): the system photo picker for images, then a copy of the
 * chosen image. A song gets an own cover once its tags are read; until then the snackbar says so. What the picker is
 * choosing for survives the activity being recreated while it is open.
 */
@Composable
private fun rememberCoverActions(snackbar: SnackbarHostState): CoverActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val choices = remember(context) { CoverChoices(context) }
    // "song:<file ID>" or "album:<album key>".
    var choosingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val say = { id: Int -> scope.launch { snackbar.showSnackbar(resources.getString(id)) }; Unit }
    /** Changes [change] outside the screen's scope, and says how it went while the screen is there. */
    val run = { change: suspend () -> CoverChoices.Result ->
        val result = CoverChoices.scope.async { change() }
        scope.launch {
            say(
                when (result.await()) {
                    CoverChoices.Result.SAVED -> R.string.cover_saved
                    CoverChoices.Result.REMOVED -> R.string.cover_removed
                    CoverChoices.Result.NOT_READ -> R.string.cover_not_read
                    CoverChoices.Result.NO_IMAGE -> R.string.cover_no_image
                    CoverChoices.Result.FAILED -> R.string.cover_failed
                },
            )
        }
        Unit
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { image ->
        val target = choosingFor
        choosingFor = null
        if (image == null || target == null) return@rememberLauncherForActivityResult
        run {
            when {
                target.startsWith(SONG_TARGET) -> choices.chooseForSong(target.removePrefix(SONG_TARGET).toLong(), image)
                else -> choices.chooseForAlbum(target.removePrefix(ALBUM_TARGET), image)
            }
        }
    }
    val pick = { target: String ->
        choosingFor = target
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return remember(choices) {
        CoverActions(
            chooseForSong = { song ->
                scope.launch { if (choices.canChooseForSong(song.id)) pick(SONG_TARGET + song.id) else say(R.string.cover_not_read) }
            },
            removeForSong = { song -> run { choices.removeForSong(song.id) } },
            songHasOwn = { song -> choices.songHasOwn(song.id) },
            chooseForAlbum = { key -> pick(ALBUM_TARGET + key) },
            removeForAlbum = { key -> run { choices.removeForAlbum(key) } },
            albumHasOwn = { key -> choices.albumHasOwn(key) },
        )
    }
}

/** The song menu's favorites and Now Playing's toggle (docs/library.md §15.2), which say when a song cannot be one yet. */
private class AppFavoriteActions(
    private val favorites: FavoriteChoices,
    private val say: (Int) -> Unit,
) {
    fun setById(fileId: Long, favorite: Boolean) {
        FavoriteChoices.scope.launch {
            when (favorites.setSong(fileId, favorite)) {
                FavoriteChoices.Result.DONE -> Unit
                FavoriteChoices.Result.NOT_READ -> say(R.string.cover_not_read)
                FavoriteChoices.Result.FAILED -> say(R.string.favorite_failed)
            }
        }
    }

    val actions = FavoriteActions(isFavorite = { song -> favorites.isFavorite(song.id) }, set = { song, on -> setById(song.id, on) })

    /** Selected songs made favorites or not at once, with a message of how many changed and how many are unread (§20.3). */
    fun batch(sayText: (String) -> Unit, resources: android.content.res.Resources) = SelectionFavorites(
        state = { ids -> favorites.batchState(ids) },
        set = { ids, favorite ->
            FavoriteChoices.scope.launch {
                val result = favorites.setSongs(ids, favorite) ?: return@launch say(R.string.favorite_failed)
                val parts = listOfNotNull(
                    result.changed.takeIf { it > 0 }?.let { n ->
                        resources.getQuantityString(if (favorite) R.plurals.selection_favorites_added else R.plurals.selection_favorites_removed, n, n)
                    },
                    result.notRead.takeIf { it > 0 }?.let { n -> resources.getQuantityString(R.plurals.selection_favorites_not_read_count, n, n) },
                )
                if (parts.isNotEmpty()) sayText(parts.joinToString(" · "))
            }
        },
    )
}

@Composable
private fun rememberFavoriteActions(favorites: FavoriteChoices, snackbar: SnackbarHostState): AppFavoriteActions {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return remember(favorites) {
        AppFavoriteActions(favorites) { id -> scope.launch { snackbar.showSnackbar(resources.getString(id)) } }
    }
}

private const val SONG_TARGET = "song:"
private const val ALBUM_TARGET = "album:"

/** The current item's media ID (docs/playback.md §9.4): a library song's, or an opened file's URI; null for nothing. */
@Composable
private fun rememberCurrentMediaId(player: Player?): String? {
    var id by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                id = player.currentMediaItem?.mediaId
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return id
}

/** Where the song of [id] belongs in the library, read off the main thread. */
private suspend fun linksOf(context: Context, id: Long): SongLinks? = withContext(Dispatchers.IO) {
    if (!Library.mayRead(context)) return@withContext null
    val hidden = LibrarySettings(context).readHiddenFiles()
    Library.read(context) { LibraryLists(it, hidden).links(id) }
}

/**
 * Now Playing's second row (docs/playback.md §6.1): the service's timer state, its commands, and the Equalizer while
 * the device has an effect panel, checked again whenever the screen resumes.
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberExtraControls(viewModel: PlayerViewModel, player: Player?): ExtraControls {
    val context = LocalContext.current
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val timerChoice by viewModel.timerChoice.collectAsStateWithLifecycle()
    var hasPanel by remember { mutableStateOf(EqualizerPanel.isAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        hasPanel = EqualizerPanel.isAvailable(context)
        onPauseOrDispose { }
    }
    // A panel must be started for a result: it reads who called it.
    val panel = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val openEqualizer = {
        val sessionId = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        try {
            panel.launch(EqualizerPanel.intent(sessionId, context.packageName))
        } catch (_: ActivityNotFoundException) {
            hasPanel = false
        }
    }
    return ExtraControls(
        extras = extras,
        setSleepTimer = viewModel::setSleepTimer,
        cancelSleepTimer = viewModel::cancelSleepTimer,
        setStopAfterCurrent = viewModel::setStopAfterCurrent,
        openEqualizer = if (hasPanel) openEqualizer else null,
        timerChoice = timerChoice,
        saveTimerChoice = viewModel::saveTimerChoice,
    )
}
