package io.github.lorddetson.piplayer.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.github.lorddetson.piplayer.debug.DebugEntry
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.ui.player.EqualizerPanel
import io.github.lorddetson.piplayer.ui.player.ExtraControls
import io.github.lorddetson.piplayer.ui.player.MiniPlayer
import io.github.lorddetson.piplayer.ui.player.NowPlaying
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.songs.SongsScreen
import kotlinx.serialization.Serializable

/** The song list with the mini player. */
@Serializable
data object SongsRoute : NavKey

@Serializable
data object NowPlayingRoute : NavKey

/**
 * The app's screens (docs/playback.md §3.1): the song list and Now Playing over it, which slides up when it opens and
 * down when it closes, following the predictive back gesture. Back is PiPlayer's only while Now Playing is open.
 */
@Composable
fun PiPlayerApp(viewModel: PlayerViewModel = viewModel()) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    val backStack = rememberNavBackStack(SongsRoute)
    val close = { if (backStack.size > 1) backStack.removeLastOrNull() }
    NavDisplay(
        backStack = backStack,
        onBack = { close() },
        transitionSpec = { slideInVertically(initialOffsetY = { it }) togetherWith ExitTransition.KeepUntilTransitionsFinished },
        popTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
        predictivePopTransitionSpec = { EnterTransition.None togetherWith slideOutVertically(targetOffsetY = { it }) },
        entryProvider = entryProvider {
            entry<SongsRoute> {
                SongsEntry(player, cover, snackbar, onSongClick = viewModel::play) {
                    if (backStack.lastOrNull() != NowPlayingRoute) backStack.add(NowPlayingRoute)
                }
            }
            entry<NowPlayingRoute> {
                Box(Modifier.fillMaxSize()) {
                    NowPlaying(player, cover, onClose = { close() }, controls = rememberExtraControls(viewModel, player))
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        },
    )
}

@Composable
private fun SongsEntry(
    player: Player?,
    cover: ImageBitmap?,
    snackbar: SnackbarHostState,
    onSongClick: (row: Int, song: Song) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    Scaffold(
        bottomBar = { MiniPlayer(player, cover, onOpen = onOpenNowPlaying) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(start = 24.dp, top = 24.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            DebugEntry()
            SongsScreen(onSongClick = onSongClick, modifier = Modifier.weight(1f))
        }
    }
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
    )
}
