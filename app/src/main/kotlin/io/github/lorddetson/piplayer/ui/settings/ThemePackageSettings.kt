package io.github.lorddetson.piplayer.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.ThemePackage
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme

internal sealed interface ThemePackageState {
    data object Idle : ThemePackageState
    data object Working : ThemePackageState
    data class Preview(val theme: ThemePackage) : ThemePackageState
    data class Notice(@StringRes val message: Int, val error: Boolean = true) : ThemePackageState
}

@Composable
internal fun ThemePackageSettings(currentTheme: String?, viewModel: SettingsViewModel) {
    val state by viewModel.themePackageState.collectAsStateWithLifecycle()
    val undo by viewModel.themePackageUndo.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::openThemePackage)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportThemePackage)
    }
    val ready = state != ThemePackageState.Working

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        if (currentTheme != null) {
            Text(stringResource(R.string.theme_package_active, currentTheme), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.testTag("theme_package_import"),
            enabled = ready,
        ) { Text(stringResource(R.string.theme_package_import)) }
        TextButton(
            onClick = { exporter.launch("PiPlayer-theme.json") },
            modifier = Modifier.testTag("theme_package_export"),
            enabled = ready,
        ) { Text(stringResource(R.string.theme_package_export)) }
        val visible = state as? ThemePackageState.Notice
        if (visible != null) {
            Text(
                text = stringResource(visible.message),
                modifier = Modifier
                    .testTag(if (visible.error) "theme_package_error" else "theme_package_status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (visible.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (undo != null) {
            TextButton(
                modifier = Modifier.testTag("theme_package_undo"),
                onClick = viewModel::undoThemePackage,
                enabled = ready,
            ) { Text(stringResource(R.string.theme_package_undo)) }
        }
    }

    val preview = state as? ThemePackageState.Preview ?: return
    ThemePackagePreview(preview.theme, viewModel::cancelThemePackagePreview, viewModel::applyThemePackage)
}

@Composable
private fun ThemePackagePreview(theme: ThemePackage, cancel: () -> Unit, apply: () -> Unit) {
    AlertDialog(
        onDismissRequest = cancel,
        modifier = Modifier.testTag("theme_package_preview"),
        title = { Text(stringResource(R.string.theme_package_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PiPlayerTheme(mode = theme.base, palette = theme.palette) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(theme.name, style = MaterialTheme.typography.titleMedium)
                            Surface(shape = MaterialTheme.shapes.small) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                    Text(stringResource(theme.base.label), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                theme.layout?.let { layout ->
                    Text(stringResource(R.string.theme_package_layout, stringResource(layout.label())))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = apply, modifier = Modifier.testTag("theme_package_apply")) {
                Text(stringResource(R.string.theme_package_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = cancel, modifier = Modifier.testTag("theme_package_cancel")) {
                Text(stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}
