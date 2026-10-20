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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.library.songs.Song

/**
 * The song list with its permission handling (docs/playback.md §2.2): it asks only on a tap, checks again whenever it
 * resumes, and offers the app's settings once the system no longer shows its dialog. A tap on a song goes to
 * [onSongClick] with its row.
 */
@Composable
fun SongsScreen(
    onSongClick: (row: Int, song: Song) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SongsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermission(granted, context.deniedForGood(granted))
    }
    LifecycleStartEffect(Unit) {
        val granted = context.hasAudioPermission()
        viewModel.onStart(granted, context.deniedForGood(granted))
        onStopOrDispose { viewModel.onStop() }
    }
    LifecycleResumeEffect(Unit) {
        val granted = context.hasAudioPermission()
        viewModel.onPermission(granted, context.deniedForGood(granted))
        onPauseOrDispose { }
    }

    SongsContent(
        state = state,
        onAllowAccess = { request.launch(AudioPermission.name) },
        onOpenSettings = { context.openAppSettings() },
        onSongClick = onSongClick,
        modifier = modifier,
    )
}

private const val PREFERENCES = "songs"
private const val DENIED_BEFORE = "audio_permission_denied_before"

private fun Context.hasAudioPermission() =
    ContextCompat.checkSelfPermission(this, AudioPermission.name) == PackageManager.PERMISSION_GRANTED

/**
 * Whether access is denied for good. The system explains why it asks only after one denial, so a rationale once seen is
 * remembered across launches: a dialog closed without an answer is then not taken for a denial.
 */
private fun Context.deniedForGood(granted: Boolean): Boolean {
    val activity = findActivity() ?: return false
    val rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, AudioPermission.name)
    val preferences = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    if (rationale) preferences.edit { putBoolean(DENIED_BEFORE, true) }
    return AudioPermissionState.deniedForGood(granted, rationale, preferences.getBoolean(DENIED_BEFORE, false))
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
