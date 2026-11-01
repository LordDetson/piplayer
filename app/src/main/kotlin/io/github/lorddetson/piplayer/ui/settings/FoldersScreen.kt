package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.rules.FolderRuleState
import io.github.lorddetson.piplayer.userdata.FolderRuleEntity

/**
 * Library folders (docs/library.md §3.6): each attached volume as a tree of the folders that hold audio, each folder
 * included, excluded or following the folder above, and below them the rules that match none of those folders.
 */
@Composable
fun FoldersScreen(onBack: () -> Unit, viewModel: FoldersViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(stringResource(R.string.settings_library_folders), onBack) { padding ->
        val current = state ?: return@SettingsScaffold
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (current.rows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.folders_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(current.rows, key = { "${it.volume}/${it.relativePath}" }) { row ->
                FolderItem(row) { viewModel.choose(row, it) }
            }
            if (current.notFound.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.folders_not_found)) }
                items(current.notFound, key = { "not found ${it.rule.volumeName}/${it.rule.relativePath}" }) { notFound ->
                    NotFoundItem(notFound) { viewModel.remove(notFound.rule) }
                }
            }
        }
    }
}

@Composable
private fun FolderItem(row: FolderRow, onChoose: (FolderRuleState) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menu = true }
                .padding(start = (24 + 24 * row.depth).dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Checkbox(checked = row.state != FolderRuleState.EXCLUDE, onCheckedChange = null)
            Column(Modifier.padding(start = 16.dp)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(row.stateLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val follow = if (row.depth == 0) R.string.folders_follow_default else R.string.folders_follow
            for ((state, label) in listOf(
                FolderRuleState.INCLUDE to R.string.folders_include,
                FolderRuleState.EXCLUDE to R.string.folders_exclude,
                FolderRuleState.NONE to follow,
            )) {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    menu = false
                    onChoose(state)
                })
            }
        }
    }
}

private val FolderRow.stateLabel: Int
    get() = when {
        rule != null -> if (rule.mode == FolderRuleEntity.INCLUDE) R.string.folders_included else R.string.folders_excluded
        state == FolderRuleState.INCLUDE -> R.string.folders_included_above
        state == FolderRuleState.EXCLUDE -> R.string.folders_excluded_above
        else -> R.string.folders_included_default
    }

@Composable
private fun NotFoundItem(notFound: RuleNotFound, onRemove: () -> Unit) {
    val rule = notFound.rule
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.relativePath.ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge)
            val label = if (rule.mode == FolderRuleEntity.INCLUDE) R.string.folders_not_found_included else R.string.folders_not_found_excluded
            Text(
                text = stringResource(label, notFound.volumeLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.folders_remove)) }
    }
}
