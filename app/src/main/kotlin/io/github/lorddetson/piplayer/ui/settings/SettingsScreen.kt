package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.BuildConfig
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.ThemeMode

/**
 * The Settings screen (docs/library.md §3.6, §14.3): sections fill in as their milestones land; M2a has Library,
 * Appearance and About.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val hidden by viewModel.hiddenFiles.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_title), onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_library))
            SwitchRow(
                stringResource(R.string.settings_show_system_sounds),
                stringResource(R.string.settings_show_system_sounds_summary),
                hidden.showSystemSounds,
                viewModel::setShowSystemSounds,
            )
            SwitchRow(
                stringResource(R.string.settings_show_recordings),
                stringResource(R.string.settings_show_recordings_summary),
                hidden.showRecordings,
                viewModel::setShowRecordings,
            )
            MinLengthChoice(hidden.minLengthSeconds, viewModel::setMinLengthSeconds)
            TextRow(
                stringResource(R.string.settings_library_folders),
                stringResource(R.string.settings_library_folders_summary),
                onOpenFolders,
            )
            TextRow(
                stringResource(R.string.settings_refresh_library),
                stringResource(R.string.settings_refresh_library_summary),
                viewModel::refreshLibrary,
            )
            TextRow(
                stringResource(R.string.settings_reread_tags),
                stringResource(R.string.settings_reread_tags_summary),
                viewModel::readAllTagsAgain,
            )
            var confirmRebuild by rememberSaveable { mutableStateOf(false) }
            TextRow(
                stringResource(R.string.settings_rebuild_library),
                stringResource(R.string.settings_rebuild_library_summary),
            ) { confirmRebuild = true }
            if (confirmRebuild) {
                AlertDialog(
                    onDismissRequest = { confirmRebuild = false },
                    title = { Text(stringResource(R.string.settings_rebuild_library_question)) },
                    text = { Text(stringResource(R.string.settings_rebuild_library_explained)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRebuild = false
                            viewModel.rebuildLibrary()
                        }) { Text(stringResource(R.string.settings_rebuild_library_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRebuild = false }) { Text(stringResource(R.string.settings_dialog_cancel)) }
                    },
                )
            }
            SectionTitle(stringResource(R.string.settings_appearance))
            ThemeChoice(theme, viewModel::setTheme)
            SectionTitle(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            TextRow(
                stringResource(R.string.settings_licenses),
                stringResource(R.string.settings_licenses_summary),
                onOpenLicenses,
            )
        }
    }
}

/** A settings page: a top bar with the title and a way back, over [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.settings_back))
                    }
                },
            )
        },
        content = content,
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun TextRow(title: String, summary: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
}

/** The length under which files are hidden, chosen in a dialog. */
@Composable
private fun MinLengthChoice(seconds: Int, onChoose: (Int) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    TextRow(stringResource(R.string.settings_min_length), minLengthLabel(seconds)) { choosing = true }
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.settings_min_length)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (length in HiddenFiles.LENGTHS) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = length == seconds, role = Role.RadioButton, onClick = {
                                choosing = false
                                onChoose(length)
                            })
                            .padding(vertical = 12.dp),
                    ) {
                        RadioButton(selected = length == seconds, onClick = null)
                        Text(
                            text = minLengthLabel(length),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
    )
}

@Composable
private fun minLengthLabel(seconds: Int) =
    if (seconds == 0) stringResource(R.string.settings_min_length_off) else stringResource(R.string.settings_min_length_value, seconds)

@Composable
private fun ThemeChoice(theme: ThemeMode, onChoose: (ThemeMode) -> Unit) {
    Text(
        text = stringResource(R.string.settings_theme),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Column(Modifier.selectableGroup()) {
        for (mode in ThemeMode.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == theme, role = Role.RadioButton, onClick = { onChoose(mode) })
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                RadioButton(selected = mode == theme, onClick = null)
                Text(
                    text = stringResource(mode.label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
