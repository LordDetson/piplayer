package io.github.lorddetson.piplayer.ui.songs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.lists.SongQuery
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.Song
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Access to music, shared by the library's lists (docs/playback.md §2.2): it is asked for only on a tap, checked again
 * whenever the screen resumes, and the app's settings are offered once the system no longer shows its dialog.
 */
@Stable
class AudioAccess internal constructor(
    granted: Boolean,
    deniedForGood: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
) {
    var granted by mutableStateOf(granted)
        internal set
    var deniedForGood by mutableStateOf(deniedForGood)
        internal set
}

@Composable
fun rememberAudioAccess(): AudioAccess {
    val context = LocalContext.current
    lateinit var access: AudioAccess
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access.update(context, granted)
    }
    access = remember {
        val granted = context.hasAudioPermission()
        AudioAccess(
            granted,
            context.deniedForGood(granted),
            request = { launcher.launch(AudioPermission.name) },
            openSettings = { context.openAppSettings() },
        )
    }
    LifecycleResumeEffect(Unit) {
        access.update(context, context.hasAudioPermission())
        onPauseOrDispose { }
    }
    return access
}

/**
 * Access granted while the process runs, from whichever tab asked: the library starts watching the index and syncs,
 * which it does only once.
 */
private fun AudioAccess.update(context: Context, granted: Boolean) {
    if (granted && !this.granted) Library.start(context)
    this.granted = granted
    deniedForGood = context.deniedForGood(granted)
}

/**
 * A song list bound to its [viewModel] and the shared [access]. A tap on a song goes to [onSongClick] with its row and
 * the list it was shown in.
 */
@Composable
fun SongsScreen(
    access: AudioAccess,
    viewModel: SongsViewModel,
    onSongClick: (row: Int, song: Song, list: SongQuery) -> Unit,
    modifier: Modifier = Modifier,
    currentSongId: Long? = null,
    menu: SongMenu? = null,
    listTag: String = SongsTags.LIST,
    emptyText: String? = null,
    discHeaders: Boolean = false,
    headerItems: Int = 0,
    header: LazyListScope.() -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val passProgress by viewModel.passProgress.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onStart(access.granted, access.deniedForGood)
        onStopOrDispose { viewModel.onStop() }
    }
    LaunchedEffect(viewModel, access.granted, access.deniedForGood) {
        viewModel.onPermission(access.granted, access.deniedForGood)
    }

    SongsContent(
        state = state,
        onAllowAccess = access.request,
        onOpenSettings = access.openSettings,
        onSongClick = { row, song -> (state as? SongsState.Loaded)?.let { onSongClick(row, song, it.query) } },
        modifier = modifier,
        passProgress = passProgress,
        onFirstVisibleSong = { viewModel.firstVisibleSong = it },
        onScrolled = viewModel::onScrolled,
        currentSongId = currentSongId,
        menu = menu,
        listTag = listTag,
        emptyText = emptyText,
        discHeaders = discHeaders,
        headerItems = headerItems,
        header = header,
    )
}

/** In the `device_state` file (docs/library.md §3.2): whether access was denied on this device. */
private val DENIED_BEFORE = booleanPreferencesKey("audio_permission_denied_before")

private fun Context.hasAudioPermission() =
    ContextCompat.checkSelfPermission(this, AudioPermission.name) == PackageManager.PERMISSION_GRANTED

/**
 * Whether access is denied for good. The system explains why it asks only after one denial, so a rationale once seen is
 * remembered across launches: a dialog closed without an answer is then not taken for a denial.
 */
private fun Context.deniedForGood(granted: Boolean): Boolean {
    val activity = findActivity() ?: return false
    val rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, AudioPermission.name)
    val file = SettingsFiles.deviceState(this)
    val deniedBefore = runBlocking {
        if (rationale) SettingsFiles.edit(file) { it[DENIED_BEFORE] = true }
        file.data.first()[DENIED_BEFORE] ?: false
    }
    return AudioPermissionState.deniedForGood(granted, rationale, deniedBefore)
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some devices have no app details screen; the card stays as it is.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
