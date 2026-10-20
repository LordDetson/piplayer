package io.github.lorddetson.piplayer.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.spike.SpikeScreen
import io.github.lorddetson.piplayer.spike.SpikeViewModel

/**
 * Debug builds: main-screen buttons that open the identification spike full screen and play a picked file through the
 * playback service. Closing the spike (or going back) cancels a running identification.
 */
@Composable
fun DebugEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    val spike: SpikeViewModel = viewModel()
    val close = {
        spike.cancel()
        open = false
    }
    OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.spike_open)) }
    if (open) {
        Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            SpikeScreen(onClose = close, viewModel = spike)
        }
    }
    DebugPlayback()
}

@Composable
private fun DebugPlayback(viewModel: DebugPlaybackViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::play)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.debug_play_file)) }
        when (state.status) {
            DebugPlaybackViewModel.Status.PLAYING -> TextButton(onClick = viewModel::togglePause) {
                Text(stringResource(R.string.debug_pause))
            }
            DebugPlaybackViewModel.Status.PAUSED, DebugPlaybackViewModel.Status.FAILED ->
                TextButton(onClick = viewModel::togglePause) { Text(stringResource(R.string.debug_resume)) }
            DebugPlaybackViewModel.Status.IDLE -> Unit
        }
    }
    val title = state.title.orEmpty()
    val line = when (state.status) {
        DebugPlaybackViewModel.Status.IDLE -> null
        DebugPlaybackViewModel.Status.PLAYING -> stringResource(R.string.debug_playing, title)
        DebugPlaybackViewModel.Status.PAUSED -> stringResource(R.string.debug_paused, title)
        DebugPlaybackViewModel.Status.FAILED -> stringResource(R.string.debug_playback_failed, state.error.orEmpty())
    }
    line?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.status == DebugPlaybackViewModel.Status.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
