package io.github.lorddetson.piplayer.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.lorddetson.piplayer.R

/** A report's whole text on a screen of its own (docs/playback.md §7.3): selectable, with Copy and Share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportScreen(text: String, onShare: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // A long trace as a single Text would be laid out whole; pieces are laid out as they scroll into view.
    val pieces = remember(text) { text.lines().chunked(LINES_PER_PIECE) { it.joinToString("\n") } }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarsLikeTheSurface()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.crash_report_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_crash_close), stringResource(R.string.crash_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { copy(context, text) }) {
                            Icon(painterResource(R.drawable.ic_crash_copy), stringResource(R.string.crash_copy))
                        }
                        IconButton(onClick = onShare) {
                            Icon(painterResource(R.drawable.ic_crash_share), stringResource(R.string.crash_share))
                        }
                    },
                )
            },
        ) { padding ->
            SelectionContainer(Modifier.padding(padding).consumeWindowInsets(padding)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(pieces) { piece ->
                        Text(piece, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The dialog's own window draws behind the system bars: their icons must stand out against the screen. */
@Composable
private fun SystemBarsLikeTheSurface() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/** Puts the report on the clipboard, up to what it holds well. */
private fun copy(context: Context, text: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.crash_report_title), text.take(COPY_CHARS))
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    when {
        text.length > COPY_CHARS -> Toast.makeText(context, R.string.crash_copied_start, Toast.LENGTH_LONG).show()
        // From Android 13 on the system confirms a copy itself.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
    }
}

private const val COPY_CHARS = 100_000
private const val LINES_PER_PIECE = 50
