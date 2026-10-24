package io.github.lorddetson.piplayer.ui.crash

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.crash.CrashKind
import io.github.lorddetson.piplayer.crash.CrashReports
import io.github.lorddetson.piplayer.crash.CrashShare
import io.github.lorddetson.piplayer.crash.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dialog after a crash (docs/playback.md §7.3): once for the reports not shown yet, with Share, Show report and
 * Close. Show report puts the report screen in its place; closing either ends the offer.
 */
@Composable
fun CrashPrompt(reports: CrashReports, share: suspend (String) -> Unit = rememberCrashShare()) {
    val pending by reports.pending.collectAsStateWithLifecycle()
    val offer = pending ?: return
    val scope = rememberCoroutineScope()
    var reading by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(offer) {
        withContext(Dispatchers.IO) { reports.markShown(offer) }
    }
    if (reading) {
        CrashReportScreen(
            text = offer.text,
            onShare = { scope.launch { share(offer.text) } },
            onClose = reports::close,
        )
    } else {
        CrashDialog(
            reports = offer.reports,
            onShare = {
                scope.launch {
                    share(offer.text)
                    reports.close()
                }
            },
            onShow = { reading = true },
            onClose = reports::close,
        )
    }
}

@Composable
private fun CrashDialog(reports: List<ReportStore.Report>, onShare: () -> Unit, onShow: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val what = if (reports.size == 1) {
        val report = reports.single()
        stringResource(R.string.crash_one, stringResource(report.kind.label), crashTime(context, report.timeMs))
    } else {
        pluralStringResource(R.plurals.crash_several, reports.size, reports.size)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(what)
                Text(stringResource(R.string.crash_explanation))
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.crash_share)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClose) { Text(stringResource(R.string.crash_close)) }
                TextButton(onClick = onShow) { Text(stringResource(R.string.crash_show)) }
            }
        },
    )
}

/** Opens the share sheet; writing the file is disk work. */
@Composable
private fun rememberCrashShare(): suspend (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val share: suspend (String) -> Unit = { text ->
            context.startActivity(withContext(Dispatchers.IO) { CrashShare.intent(context, text) })
        }
        share
    }
}

private val CrashKind.label: Int
    get() = when (this) {
        CrashKind.JAVA -> R.string.crash_kind_java
        CrashKind.NATIVE -> R.string.crash_kind_native
        CrashKind.ANR -> R.string.crash_kind_anr
    }

/** When a crash happened, as the user's settings write a date and a time. */
fun crashTime(context: Context, timeMs: Long): String = DateUtils.formatDateTime(
    context,
    timeMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
)
