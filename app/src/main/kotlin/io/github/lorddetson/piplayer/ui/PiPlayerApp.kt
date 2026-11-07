package io.github.lorddetson.piplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.lists.LibraryLists
import io.github.lorddetson.piplayer.library.lists.SongLinks
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.ui.library.AlbumPage
import io.github.lorddetson.piplayer.ui.library.ArtistPage
import io.github.lorddetson.piplayer.ui.library.FolderPage
import io.github.lorddetson.piplayer.ui.library.LibraryHost
import io.github.lorddetson.piplayer.ui.library.LibraryScreen
import io.github.lorddetson.piplayer.ui.onboarding.OnboardingGate
import io.github.lorddetson.piplayer.ui.onboarding.rememberOnboarding
import io.github.lorddetson.piplayer.ui.settings.LicenseTextScreen
import io.github.lorddetson.piplayer.ui.settings.LicensesScreen
import io.github.lorddetson.piplayer.ui.songs.SongMenu
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.lorddetson.piplayer.ui.player.QueueScreen
import io.github.lorddetson.piplayer.ui.player.QueueTarget
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.ui.crash.CrashPrompt
import io.github.lorddetson.piplayer.ui.player.EqualizerPanel
import io.github.lorddetson.piplayer.ui.player.ExtraControls
import io.github.lorddetson.piplayer.ui.player.MiniPlayer
import io.github.lorddetson.piplayer.ui.player.NowPlaying
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.settings.FoldersScreen
import io.github.lorddetson.piplayer.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

/** The library tabs with the mini player (docs/library.md §6.1). */
@Serializable
data object SongsRoute : NavKey

/** An album page, found by its album key (docs/library.md §6.5). */
@Serializable
data class AlbumRoute(val key: String) : NavKey

/** An artist page, found by its artist key. */
@Serializable
data class ArtistRoute(val key: String) : NavKey

/** A folder page, found by its volume and relative path. */
@Serializable
data class LibraryFolderRoute(val volume: String, val relativePath: String) : NavKey

@Serializable
data object NowPlayingRoute : NavKey

/** The queue screen (docs/playback.md §9.2), opened from Now Playing. */
@Serializable
data object QueueRoute : NavKey

/** The Library folders screen (docs/library.md §3.6). */
@Serializable
data object FoldersRoute : NavKey

/** The Settings screen (docs/library.md §3.6). */
@Serializable
data object SettingsRoute : NavKey

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
fun PiPlayerApp(viewModel: PlayerViewModel = viewModel()) {
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
    val backStack = rememberNavBackStack(SongsRoute)
    val close = { if (backStack.size > 1) backStack.removeLastOrNull() }
    // A page opens over the library or the page it was opened from; Now Playing gives way to it.
    val open = { route: NavKey ->
        if (backStack.lastOrNull() == NowPlayingRoute) backStack.removeLastOrNull()
        if (backStack.lastOrNull() != route) backStack.add(route)
    }
    val context = LocalContext.current
    val currentSongId = rememberCurrentSongId(player)
    val covers = rememberCoverActions(snackbar)
    val menu = remember {
        SongMenu(
            links = { song -> linksOf(context, song.id) },
            openAlbum = { open(AlbumRoute(it)) },
            openArtist = { open(ArtistRoute(it)) },
            openFolder = { volume, path -> open(LibraryFolderRoute(volume, path)) },
            playNext = { viewModel.addToQueue(QueueTarget.Songs(listOf(it.id)), next = true) },
            addToQueue = { viewModel.addToQueue(QueueTarget.Songs(listOf(it.id)), next = false) },
            covers = covers,
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
        addToQueue = viewModel::addToQueue,
        openNowPlaying = { if (backStack.lastOrNull() != NowPlayingRoute) backStack.add(NowPlayingRoute) },
        openAlbum = menu.openAlbum,
        openArtist = menu.openArtist,
        openFolder = menu.openFolder,
        covers = covers,
    )
    // What the artist and the album on Now Playing open (docs/library.md §6.1).
    var playingLinks by remember { mutableStateOf<SongLinks?>(null) }
    LaunchedEffect(currentSongId) {
        playingLinks = null
        playingLinks = currentSongId?.let { linksOf(context, it) }
    }
    NavDisplay(
        backStack = backStack,
        onBack = { close() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        transitionSpec = { slideInVertically(initialOffsetY = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished },
        popTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
        predictivePopTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
        entryProvider = entryProvider {
            entry<SongsRoute> {
                OnboardingGate(
                    onboarding,
                    bottomBar = { MiniPlayer(host.player, host.cover, onOpen = host.openNowPlaying) },
                    snackbarHost = { SnackbarHost(host.snackbar) },
                ) {
                    LibraryScreen(host, onOpenSettings = { if (backStack.lastOrNull() != SettingsRoute) backStack.add(SettingsRoute) })
                }
            }
            entry<AlbumRoute>(metadata = FROM_THE_SIDE) { route -> AlbumPage(route.key, host, onBack = { close() }) }
            entry<ArtistRoute>(metadata = FROM_THE_SIDE) { route -> ArtistPage(route.key, host, onBack = { close() }) }
            entry<LibraryFolderRoute>(metadata = FROM_THE_SIDE) { route ->
                FolderPage(route.volume, route.relativePath, host, onBack = { close() })
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
                )
            }
            entry<SettingsRoute>(metadata = FROM_THE_SIDE) {
                SettingsScreen(
                    onBack = { close() },
                    onOpenFolders = { if (backStack.lastOrNull() != FoldersRoute) backStack.add(FoldersRoute) },
                    onOpenLicenses = { if (backStack.lastOrNull() != LicensesRoute) backStack.add(LicensesRoute) },
                )
            }
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
                    val links = playingLinks
                    val scope = rememberCoroutineScope()
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
                        controls = rememberExtraControls(viewModel, player),
                        onOpenArtist = links?.artists?.firstOrNull()?.let {
                            { openLatest { latest -> latest.artists.firstOrNull()?.let { ArtistRoute(it.key) } } }
                        },
                        onOpenAlbum = links?.albumKey?.let { { openLatest { latest -> latest.albumKey?.let(::AlbumRoute) } } },
                        onOpenQueue = { if (backStack.lastOrNull() != QueueRoute) backStack.add(QueueRoute) },
                        coverName = coverName,
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        },
    )
    if (CrashReports.promptEnabled) CrashPrompt(CrashReports.of(LocalContext.current))
}

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

private const val SONG_TARGET = "song:"
private const val ALBUM_TARGET = "album:"

/** The current song's ID in the library, or null for an opened file or nothing. */
@Composable
private fun rememberCurrentSongId(player: Player?): Long? {
    // An added entry plays a library song too (docs/playback.md §9.4).
    var id by remember(player) { mutableStateOf(player?.currentMediaItem?.mediaId?.let(QueueIds::fileId)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                id = player.currentMediaItem?.mediaId?.let(QueueIds::fileId)
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
