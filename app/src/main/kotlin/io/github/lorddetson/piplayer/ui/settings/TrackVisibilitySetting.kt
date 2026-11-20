package io.github.lorddetson.piplayer.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.diagnostics.AndroidTrackVisibility
import io.github.lorddetson.piplayer.library.diagnostics.TrackVisibilityFilter
import io.github.lorddetson.piplayer.library.diagnostics.TrackVisibilityFinding
import io.github.lorddetson.piplayer.settings.HiddenFiles
import java.text.NumberFormat

@Composable
internal fun TrackVisibilitySetting(
    settings: HiddenFiles,
    check: (suspend (Uri, HiddenFiles) -> TrackVisibilityFinding)? = null,
) {
    val context = LocalContext.current.applicationContext
    val checker = remember(context) { AndroidTrackVisibility(context) }
    var request by remember { mutableStateOf<TrackVisibilityRequest?>(null) }
    var finding by remember { mutableStateOf<TrackVisibilityFinding?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) request = TrackVisibilityRequest(uri, settings)
    }
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        val result = check?.invoke(current.uri, current.settings) ?: checker.check(current.uri, current.settings)
        if (request === current) {
            finding = result
            request = null
        }
    }

    TextRow(stringResource(R.string.track_visibility_check), stringResource(R.string.track_visibility_summary)) {
        if (request == null) picker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
    }
    if (request != null) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(Modifier.weight(1f))
            TextButton(onClick = { request = null }) { Text(stringResource(R.string.settings_dialog_cancel)) }
        }
    }
    finding?.let { report ->
        val close = { finding = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.track_visibility_check)) },
            text = { TrackVisibilityExplanation(report) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.track_visibility_close)) } },
        )
    }
}

private class TrackVisibilityRequest(val uri: Uri, val settings: HiddenFiles)

@Composable
private fun TrackVisibilityExplanation(report: TrackVisibilityFinding) {
    val message = when (report) {
        TrackVisibilityFinding.Inaccessible -> R.string.track_visibility_inaccessible
        TrackVisibilityFinding.NoLibraryPermission -> R.string.track_visibility_no_permission
        TrackVisibilityFinding.Undetermined -> R.string.track_visibility_unknown
        TrackVisibilityFinding.NotInMediaStore -> R.string.track_visibility_not_indexed
        TrackVisibilityFinding.PendingLibrary -> R.string.track_visibility_pending_library
        TrackVisibilityFinding.Visible -> R.string.track_visibility_visible
        is TrackVisibilityFinding.Filtered -> R.string.track_visibility_hidden
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(message))
        if (report is TrackVisibilityFinding.Filtered) {
            for (filter in report.filters) {
                val reason = when (filter) {
                    TrackVisibilityFilter.EXCLUDED_FOLDER -> stringResource(R.string.track_visibility_excluded_folder)
                    TrackVisibilityFilter.SYSTEM_SOUND -> stringResource(R.string.track_visibility_system_sound)
                    TrackVisibilityFilter.RECORDING -> stringResource(R.string.track_visibility_recording)
                    TrackVisibilityFilter.MINIMUM_LENGTH -> {
                        val seconds = requireNotNull(report.durationMs) / 1_000.0
                        val locale = LocalConfiguration.current.locales[0]
                        val duration = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(seconds)
                        stringResource(R.string.track_visibility_too_short, duration, requireNotNull(report.minimumSeconds))
                    }
                }
                Text(reason)
            }
        }
    }
}
