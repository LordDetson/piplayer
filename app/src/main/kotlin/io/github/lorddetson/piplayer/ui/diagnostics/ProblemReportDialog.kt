package io.github.lorddetson.piplayer.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.crash.DeviceInfo
import io.github.lorddetson.piplayer.diagnostics.ProblemReport
import io.github.lorddetson.piplayer.library.details.TraceRun

/** Shows exactly what Copy will put on the clipboard, with an opt-in for the available trace. */
@Composable
fun ProblemReportDialog(onClose: () -> Unit, trace: List<TraceRun> = emptyList()) {
    val context = LocalContext.current
    val title = stringResource(R.string.problem_report_title)
    val device = remember { DeviceInfo.current() }
    var includeTrace by rememberSaveable { mutableStateOf(false) }
    val report = remember(device, trace, includeTrace) {
        ProblemReport.text(device, if (includeTrace) trace else emptyList())
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (trace.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(includeTrace, role = Role.Checkbox) { includeTrace = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(includeTrace, onCheckedChange = null)
                        Text(stringResource(R.string.problem_report_include_trace))
                    }
                }
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, report))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.details_copied, Toast.LENGTH_SHORT).show()
                }
                onClose()
            }) { Text(stringResource(R.string.problem_report_copy)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}
