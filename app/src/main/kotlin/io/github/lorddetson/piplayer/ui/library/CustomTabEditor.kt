package io.github.lorddetson.piplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.customtabs.CustomTabDefinition
import io.github.lorddetson.piplayer.library.customtabs.CustomTabRecord
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSource
import io.github.lorddetson.piplayer.library.customtabs.CustomTabSourceDraft
import io.github.lorddetson.piplayer.library.customtabs.CustomTabTemplateSource
import io.github.lorddetson.piplayer.library.customtabs.MAX_TAB_HINT_LENGTH
import io.github.lorddetson.piplayer.library.customtabs.TabTagRule
import io.github.lorddetson.piplayer.library.customtabs.TabTagRuleSet
import io.github.lorddetson.piplayer.library.customtabs.TabTextComparison
import io.github.lorddetson.piplayer.library.customtabs.TabTextField
import io.github.lorddetson.piplayer.library.customtabs.TagRuleMatch
import io.github.lorddetson.piplayer.library.customtabs.watchCustomTabFolders
import io.github.lorddetson.piplayer.library.db.FolderKey
import io.github.lorddetson.piplayer.library.lists.FolderLevels
import io.github.lorddetson.piplayer.library.lists.ListKind
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabWrite
import io.github.lorddetson.piplayer.settings.librarytabs.CustomTabsSettings
import io.github.lorddetson.piplayer.ui.playlists.smartListName
import io.github.lorddetson.piplayer.userdata.PlaylistSummary
import io.github.lorddetson.piplayer.userdata.UserData
import io.github.lorddetson.piplayer.userdata.byName
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal sealed interface CustomTabDialog {
    data object Create : CustomTabDialog
    data class Edit(val tab: CustomTabRecord) : CustomTabDialog
    data class Rebind(val tab: CustomTabRecord) : CustomTabDialog
    data class Delete(val tab: CustomTabRecord) : CustomTabDialog
}

@Composable
internal fun CustomTabDialogs(
    dialog: CustomTabDialog?,
    settings: CustomTabsSettings,
    foldersAllowed: Boolean,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.custom_tab_write_failed)
    when (dialog) {
        CustomTabDialog.Create -> CustomTabEditor(
            existing = null, foldersAllowed, onCancel = onDismiss, onSaved = { onCreated(); onDismiss() },
        ) { name, draft -> settings.create(name, checkNotNull(draft), select = true) }
        is CustomTabDialog.Edit -> CustomTabEditor(dialog.tab, foldersAllowed, onDismiss, onDismiss) { name, draft ->
            settings.edit(dialog.tab, name, draft)
        }
        is CustomTabDialog.Rebind -> CustomTabSourcePicker(dialog.tab.definition.source.kindForEditor, foldersAllowed, onDismiss) { draft ->
            onDismiss()
            scope.launch {
                if (settings.rebind(dialog.tab, draft) != CustomTabWrite.STORED) snackbar.showSnackbar(failed)
            }
        }
        is CustomTabDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.custom_tab_delete_confirm, dialog.tab.definition.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    scope.launch {
                        if (settings.delete(dialog.tab) != CustomTabWrite.STORED) snackbar.showSnackbar(failed)
                    }
                }) { Text(stringResource(R.string.playlist_delete)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        )
        null -> Unit
    }
}

internal enum class CustomTabSourceKind(val label: Int) {
    PLAYLIST(R.string.custom_tab_source_playlist),
    FOLDER(R.string.custom_tab_source_folder),
    SMART(R.string.custom_tab_source_smart),
    RULES(R.string.custom_tab_source_rules),
}

internal val CustomTabTemplateSource.kindForEditor: CustomTabSourceKind get() = when (this) {
    is CustomTabTemplateSource.Playlist -> CustomTabSourceKind.PLAYLIST
    is CustomTabTemplateSource.Folder -> CustomTabSourceKind.FOLDER
    is CustomTabTemplateSource.Collection -> CustomTabSourceKind.SMART
    is CustomTabTemplateSource.Rules -> CustomTabSourceKind.RULES
}

@Composable
internal fun CustomTabEditor(
    existing: CustomTabRecord?,
    foldersAllowed: Boolean,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    save: suspend (String, CustomTabSourceDraft?) -> CustomTabWrite,
) {
    val previous = existing?.definition?.source
    val previousKind = previous?.kindForEditor
    val oldRules = (previous as? CustomTabTemplateSource.Rules)?.rules
    var name by remember(existing) { mutableStateOf(existing?.definition?.name.orEmpty()) }
    var sourceKind by remember(existing) { mutableStateOf(previousKind ?: CustomTabSourceKind.PLAYLIST) }
    var choice by remember(existing) { mutableStateOf<CustomTabSourceDraft?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var match by remember(existing) { mutableStateOf(oldRules?.match ?: TagRuleMatch.ALL) }
    var rules by remember(existing) { mutableStateOf(oldRules?.rules?.map(::RuleDraft) ?: listOf(RuleDraft())) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val ruleSet = remember(match, rules) {
        val parsed = rules.mapNotNull(RuleDraft::toRule)
        if (parsed.size == rules.size) TabTagRuleSet.create(match, parsed) else null
    }
    val source = if (sourceKind == CustomTabSourceKind.RULES) ruleSet?.let(CustomTabSourceDraft::Rules) else choice
    val keepsSource = sourceKind == previousKind && sourceKind != CustomTabSourceKind.RULES
    val canSave = CustomTabDefinition.isValidName(name) && (source != null || keepsSource)

    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(stringResource(if (existing == null) R.string.custom_tab_create else R.string.custom_tab_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_tab_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("custom_tab_name"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in CustomTabSourceKind.entries) {
                        FilterChip(
                            selected = sourceKind == kind,
                            onClick = {
                                if (sourceKind != kind) choice = null
                                sourceKind = kind
                            },
                            label = { Text(stringResource(kind.label)) },
                            modifier = Modifier.testTag("custom_tab_source_${kind.name.lowercase()}"),
                        )
                    }
                }
                if (sourceKind == CustomTabSourceKind.RULES) {
                    RulesEditor(
                        match,
                        rules,
                        onMatch = { match = it },
                        onChange = { index, rule -> rules = rules.toMutableList().apply { set(index, rule) } },
                        onAdd = { rules = rules + RuleDraft() },
                        onRemove = { index -> rules = rules.filterIndexed { at, _ -> at != index } },
                    )
                } else {
                    val label = choice?.let { choiceName(it) }
                        ?: existing?.takeIf { keepsSource }?.let { currentSourceName(it, foldersAllowed) }
                    if (label != null) Text(label)
                    TextButton(onClick = { choosing = true }, modifier = Modifier.testTag("custom_tab_select_source")) {
                        Text(stringResource(R.string.custom_tab_choose))
                    }
                }
                if (failed) Text(stringResource(R.string.custom_tab_write_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val savedName = name
                    saving = true
                    scope.launch {
                        val result = save(savedName, source)
                        saving = false
                        failed = result != CustomTabWrite.STORED
                        if (!failed) onSaved()
                    }
                },
                enabled = canSave && !saving,
                modifier = Modifier.testTag("custom_tab_save"),
            ) { Text(stringResource(R.string.playlist_save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
    if (choosing) CustomTabSourcePicker(sourceKind, foldersAllowed, onCancel = { choosing = false }) {
        choice = it
        choosing = false
    }
}

@Composable
internal fun CustomTabSourcePicker(
    kind: CustomTabSourceKind,
    foldersAllowed: Boolean,
    onCancel: () -> Unit,
    onChoose: (CustomTabSourceDraft) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.custom_tab_choose)) },
        text = {
            when (kind) {
                CustomTabSourceKind.PLAYLIST -> {
                    val playlists = remember(context) { UserData.playlists(context).playlistsFlow() }
                    val rows by playlists.collectAsStateWithLifecycle<List<PlaylistSummary>?>(null)
                    val ordered = remember(rows) { rows?.byName() }
                    SourceChoices(ordered, PlaylistSummary::id, { it.name }, { "custom_tab_playlist_${it.id}" }) {
                        onChoose(CustomTabSourceDraft.Playlist(it.id, it.createdAt, it.name.portableHint()))
                    }
                }
                CustomTabSourceKind.FOLDER -> {
                    val folderFlow = if (foldersAllowed) remember(context) { watchCustomTabFolders(context) } else null
                    val folders = folderFlow?.collectAsStateWithLifecycle<List<FolderKey>?>(null)?.value
                    SourceChoices(
                        if (foldersAllowed) folders else emptyList(),
                        FolderKey::key,
                        { folder -> folderLabel(folder) },
                        { "custom_tab_folder_${it.key}" },
                    ) {
                        onChoose(CustomTabSourceDraft.Folder(it, it.relativePath.ifEmpty { "/" }.portableHint()))
                    }
                }
                CustomTabSourceKind.SMART ->
                    SourceChoices(ListKind.SMART, ListKind::token, { stringResource(smartListName(it)) }, { "custom_tab_smart_${it.token}" }) {
                        onChoose(CustomTabSourceDraft.Collection(it))
                    }
                CustomTabSourceKind.RULES -> Unit
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun <T> SourceChoices(
    rows: List<T>?,
    key: (T) -> Any,
    label: @Composable (T) -> String,
    tag: (T) -> String,
    onChoose: (T) -> Unit,
) {
    when {
        rows == null -> Loading(Modifier.fillMaxWidth().heightIn(min = 48.dp))
        rows.isEmpty() -> Text(stringResource(R.string.custom_tab_no_sources))
        else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 312.dp)) {
            items(rows, key = key) { row ->
                Text(
                    text = label(row),
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(row) }.testTag(tag(row))
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

private fun String.portableHint(): String =
    filterNot(Char::isISOControl).trim().take(MAX_TAB_HINT_LENGTH).trimEnd(Char::isHighSurrogate).ifBlank { "/" }

@Composable
private fun folderLabel(folder: FolderKey): String = stringResource(
    R.string.custom_tab_folder_label,
    if (folder.volume == FolderLevels.PRIMARY) stringResource(R.string.internal_storage) else folder.volume,
    folder.relativePath.ifEmpty { "/" },
)

@Composable
private fun currentSourceName(record: CustomTabRecord, foldersAllowed: Boolean): String? {
    val context = LocalContext.current.applicationContext
    val source = record.resolvedSource
    return when (source) {
        is CustomTabSource.Playlist -> {
            val playlist = remember(context, source.id) {
                UserData.playlists(context).playlistFlow(source.id).map { GroupsState.Loaded(it) }
            }
            when (val result = playlist.collectAsStateWithLifecycle<GroupsState<PlaylistSummary?>>(GroupsState.Loading).value) {
                GroupsState.Loading -> null
                is GroupsState.Loaded -> result.value?.takeIf { it.createdAt == record.playlistCreatedAt }?.name
                    ?: stringResource(R.string.custom_tab_missing_playlist)
            }
        }
        is CustomTabSource.Folder -> if (foldersAllowed) {
            val folders = remember(context) { watchCustomTabFolders(context) }
                .collectAsStateWithLifecycle<List<FolderKey>?>(null).value
            when {
                folders == null -> null
                source.key in folders -> folderLabel(source.key)
                else -> stringResource(R.string.custom_tab_missing_folder)
            }
        } else {
            stringResource(R.string.custom_tab_missing_folder)
        }
        else -> templateName(record.definition.source)
    }
}

@Composable
private fun choiceName(source: CustomTabSourceDraft): String = when (source) {
    is CustomTabSourceDraft.Playlist -> source.hint
    is CustomTabSourceDraft.Folder -> source.hint
    is CustomTabSourceDraft.Collection -> stringResource(smartListName(source.kind))
    is CustomTabSourceDraft.Rules -> stringResource(R.string.custom_tab_source_rules)
}

@Composable
private fun templateName(source: CustomTabTemplateSource): String = when (source) {
    is CustomTabTemplateSource.Playlist -> source.hint
    is CustomTabTemplateSource.Folder -> source.hint
    is CustomTabTemplateSource.Collection -> stringResource(smartListName(source.kind))
    is CustomTabTemplateSource.Rules -> stringResource(R.string.custom_tab_source_rules)
}

private enum class RuleField(val label: Int, val textField: TabTextField? = null) {
    TITLE(R.string.details_field_title, TabTextField.TITLE),
    ARTIST(R.string.details_field_artist, TabTextField.ARTIST),
    ALBUM(R.string.details_field_album, TabTextField.ALBUM),
    GENRE(R.string.details_field_genre, TabTextField.GENRE),
    COMPOSER(R.string.details_field_composer, TabTextField.COMPOSER),
    YEAR(R.string.sort_year),
}

private data class RuleDraft(
    val field: RuleField = RuleField.TITLE,
    val comparison: TabTextComparison = TabTextComparison.CONTAINS,
    val value: String = "",
    val from: String = "",
    val through: String = "",
) {
    fun toRule(): TabTagRule? {
        val textField = field.textField
        if (textField != null) return TabTagRule.Text(textField, comparison, value)
        val start = from.trim().toIntOrNull()
        val end = through.trim().toIntOrNull()
        if (start == null && from.isNotBlank() || end == null && through.isNotBlank()) return null
        return TabTagRule.Year(start, end)
    }
}

private fun RuleDraft(rule: TabTagRule): RuleDraft = when (rule) {
    is TabTagRule.Text -> RuleDraft(RuleField.entries.first { it.textField == rule.field }, rule.comparison, rule.text)
    is TabTagRule.Year -> RuleDraft(field = RuleField.YEAR, from = rule.from?.toString().orEmpty(), through = rule.through?.toString().orEmpty())
}

@Composable
private fun RulesEditor(
    match: TagRuleMatch,
    rules: List<RuleDraft>,
    onMatch: (TagRuleMatch) -> Unit,
    onChange: (Int, RuleDraft) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in TagRuleMatch.entries) {
            val title = if (mode == TagRuleMatch.ALL) R.string.custom_tab_match_all else R.string.custom_tab_match_any
            FilterChip(match == mode, { onMatch(mode) }, label = { Text(stringResource(title)) })
        }
    }
    rules.forEachIndexed { index, rule ->
        RuleEditor(index, rule, change = { onChange(index, it) }, remove = { onRemove(index) }.takeIf { rules.size > 1 })
    }
    TextButton(onClick = onAdd, enabled = rules.size < 32) { Text(stringResource(R.string.custom_tab_rule_add)) }
}

@Composable
private fun RuleEditor(index: Int, rule: RuleDraft, change: (RuleDraft) -> Unit, remove: (() -> Unit)?) {
    var fieldsOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { fieldsOpen = true }, modifier = Modifier.testTag("custom_tab_rule_field_$index")) {
                Text(stringResource(rule.field.label))
            }
            DropdownMenu(expanded = fieldsOpen, onDismissRequest = { fieldsOpen = false }) {
                for (field in RuleField.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(field.label)) },
                        onClick = { change(rule.copy(field = field)); fieldsOpen = false },
                        modifier = Modifier.testTag("custom_tab_rule_${field.name.lowercase()}_$index"),
                    )
                }
            }
        }
        if (remove != null) {
            IconButton(onClick = remove) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.custom_tab_rule_remove))
            }
        }
    }
    if (rule.field == RuleField.YEAR) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val number = KeyboardOptions(keyboardType = KeyboardType.Number)
            OutlinedTextField(rule.from, { change(rule.copy(from = it)) }, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.custom_tab_year_from)) }, singleLine = true, keyboardOptions = number)
            OutlinedTextField(rule.through, { change(rule.copy(through = it)) }, modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.custom_tab_year_through)) }, singleLine = true, keyboardOptions = number)
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (comparison in TabTextComparison.entries) {
                val label = if (comparison == TabTextComparison.EXACT) R.string.custom_tab_rule_exact else R.string.custom_tab_rule_contains
                FilterChip(rule.comparison == comparison, { change(rule.copy(comparison = comparison)) },
                    label = { Text(stringResource(label)) })
            }
        }
        OutlinedTextField(
            value = rule.value,
            onValueChange = { change(rule.copy(value = it)) },
            label = { Text(stringResource(R.string.custom_tab_rule_value)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("custom_tab_rule_value_$index"),
        )
    }
}
