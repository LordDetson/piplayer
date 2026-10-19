package io.github.lorddetson.piplayer.spike

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import kotlinx.coroutines.launch

/**
 * The M0 identification spike (debug builds only): picks a file through the system picker and shows the raw candidates
 * of AcoustID and of the MusicBrainz text search as a plain-text report that can be copied.
 */
@Composable
fun SpikeScreen(onClose: () -> Unit, viewModel: SpikeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::identify)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.spike_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.spike_close)) }
            }
            Text(stringResource(R.string.spike_explanation), style = MaterialTheme.typography.bodyMedium)
            if (!SpikeServices.hasAcoustIdKey) {
                Text(
                    stringResource(R.string.spike_no_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = { picker.launch(arrayOf("audio/*")) }, enabled = state !is SpikeViewModel.State.Running) {
                Text(stringResource(R.string.spike_choose_file))
            }

            when (val current = state) {
                SpikeViewModel.State.Idle -> Unit
                is SpikeViewModel.State.Running -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stageText(current.stage))
                }
                is SpikeViewModel.State.Failed ->
                    Text(stringResource(R.string.spike_failed, current.error), color = MaterialTheme.colorScheme.error)
                is SpikeViewModel.State.Finished -> {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PiPlayer spike", current.text)))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.spike_copy_report))
                    }
                    SelectionContainer {
                        Text(
                            text = current.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stageText(stage: SpikeStage): String = stringResource(
    when (stage) {
        SpikeStage.READING_TAGS -> R.string.spike_stage_reading_tags
        SpikeStage.FINGERPRINTING -> R.string.spike_stage_fingerprinting
        SpikeStage.ACOUSTID -> R.string.spike_stage_acoustid
        SpikeStage.TEXT_SEARCH, SpikeStage.DONE -> R.string.spike_stage_text_search
    },
)
