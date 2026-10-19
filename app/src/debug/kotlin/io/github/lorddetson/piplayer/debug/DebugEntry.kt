package io.github.lorddetson.piplayer.debug

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lorddetson.piplayer.R
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.spike.SpikeScreen
import io.github.lorddetson.piplayer.spike.SpikeViewModel

/**
 * Debug builds: a main-screen button that opens the identification spike full screen. Closing it (or going back)
 * cancels a running identification.
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
}
