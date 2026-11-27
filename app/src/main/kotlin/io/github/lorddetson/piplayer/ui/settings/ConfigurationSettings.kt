package io.github.lorddetson.piplayer.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationChange
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationPreview
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationSection
import io.github.lorddetson.piplayer.tagging.write.TagCoverPreset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal sealed interface ConfigurationSettingsState {
    data object Idle : ConfigurationSettingsState
    data object Working : ConfigurationSettingsState
    data object PickingImport : ConfigurationSettingsState
    data object ExportReady : ConfigurationSettingsState
    data object PickingExport : ConfigurationSettingsState
    data class Preview(val configuration: ConfigurationPreview) : ConfigurationSettingsState
    data class Notice(@StringRes val message: Int, val error: Boolean = true) : ConfigurationSettingsState

    val canStart: Boolean get() = this == Idle || this is Notice
}

@Composable
internal fun ConfigurationSettings(viewModel: SettingsViewModel) {
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.configurationState.collectAsStateWithLifecycle()
    val undo by viewModel.configurationUndo.collectAsStateWithLifecycle()
    val recreate by viewModel.configurationRecreation.collectAsStateWithLifecycle()
    LaunchedEffect(activity, recreate) {
        if (recreate && activity != null && !activity.isDestroyed && !activity.isFinishing &&
            viewModel.takeConfigurationRecreation()
        ) {
            activity.recreate()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.openConfiguration(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.finishConfigurationExport(uri)
    }
    val exportReady = state == ConfigurationSettingsState.ExportReady
    LaunchedEffect(lifecycle, exportReady, recreate) {
        if (exportReady && !recreate) lifecycle.withResumed {
            if (viewModel.beginConfigurationExportPicker()) {
                launchConfigurationPicker(viewModel) { exporter.launch("PiPlayer-configuration.json") }
            }
        }
    }
    val ready = state.canStart
    SectionTitle(stringResource(R.string.configuration_settings))
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.configuration_scope), style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = viewModel::prepareConfigurationExport,
            enabled = ready,
            modifier = Modifier.testTag("configuration_export"),
        ) { Text(stringResource(R.string.configuration_export)) }
        TextButton(
            onClick = {
                if (viewModel.beginConfigurationImportPicker()) {
                    launchConfigurationPicker(viewModel) {
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                }
            },
            enabled = ready,
            modifier = Modifier.testTag("configuration_import"),
        ) { Text(stringResource(R.string.configuration_import)) }
        if (state == ConfigurationSettingsState.Working || exportReady) {
            Text(
                stringResource(R.string.configuration_working),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val notice = state as? ConfigurationSettingsState.Notice
        if (notice != null) {
            Text(
                stringResource(notice.message),
                modifier = Modifier
                    .testTag(if (notice.error) "configuration_error" else "configuration_status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (notice.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (undo != null) {
            TextButton(
                onClick = viewModel::undoConfiguration,
                enabled = ready,
                modifier = Modifier.testTag("configuration_undo"),
            ) { Text(stringResource(R.string.configuration_undo)) }
        }
    }
    val preview = (state as? ConfigurationSettingsState.Preview)?.configuration ?: return
    val savedThemes by viewModel.savedThemes.collectAsStateWithLifecycle(initialValue = emptyList())
    val themeNames = remember(savedThemes, preview) {
        buildMap {
            savedThemes.forEach { put(it.id, it.name) }
            for (change in preview.changes) {
                val parts = change.path.split('.')
                if (parts.size == 4 && parts[1] == "savedThemes" && parts[3] == "name") {
                    (change.after as? JsonPrimitive)?.content?.let { name -> getOrPut(parts[2]) { name } }
                }
            }
        }
    }
    ConfigurationPreviewDialog(
        preview,
        themeNames,
        cancel = viewModel::cancelConfigurationPreview,
        apply = viewModel::applyConfiguration,
    )
}

private inline fun launchConfigurationPicker(viewModel: SettingsViewModel, launch: () -> Unit) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        viewModel.configurationPickerUnavailable()
    } catch (_: SecurityException) {
        viewModel.configurationPickerUnavailable()
    }
}

@Composable
private fun ConfigurationPreviewDialog(
    preview: ConfigurationPreview,
    themeNames: Map<String, String>,
    cancel: () -> Unit,
    apply: () -> Unit,
) {
    val grouped = remember(preview) { preview.changes.groupBy(ConfigurationChange::section) }
    val hasChanges = grouped.isNotEmpty()
    AlertDialog(
        onDismissRequest = cancel,
        modifier = Modifier.testTag("configuration_preview"),
        title = { Text(stringResource(R.string.configuration_preview)) },
        text = {
            if (hasChanges) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ConfigurationSection.APPEARANCE in grouped) {
                        item { Text(stringResource(R.string.configuration_device_controls)) }
                    }
                    grouped.forEach { (section, changes) ->
                        item {
                            Text(
                                stringResource(section.label()),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(changes, key = { it.path }) { ConfigurationChangeRow(it, themeNames) }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.configuration_no_changes),
                    modifier = Modifier.testTag("configuration_no_changes"),
                )
            }
        },
        confirmButton = {
            if (hasChanges) {
                TextButton(onClick = apply, modifier = Modifier.testTag("configuration_apply")) {
                    Text(stringResource(R.string.configuration_apply))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = cancel, modifier = Modifier.testTag("configuration_cancel")) {
                Text(stringResource(if (hasChanges) R.string.settings_dialog_cancel else R.string.configuration_close))
            }
        },
    )
}

private fun ConfigurationSection.label() = when (this) {
    ConfigurationSection.LANGUAGE -> R.string.app_language
    ConfigurationSection.LIBRARY -> R.string.settings_library
    ConfigurationSection.TAG_WRITING -> R.string.tag_writing_settings
    ConfigurationSection.SLEEP_TIMER -> R.string.player_sleep_timer
    ConfigurationSection.APPEARANCE -> R.string.settings_appearance
}

@Composable
private fun ConfigurationChangeRow(change: ConfigurationChange, themeNames: Map<String, String>) {
    val label = when (change.path) {
        "language" -> stringResource(R.string.app_language)
        "library.showSystemSounds" -> stringResource(R.string.settings_show_system_sounds)
        "library.showRecordings" -> stringResource(R.string.settings_show_recordings)
        "library.minLengthSeconds" -> stringResource(R.string.settings_min_length)
        "library.legacyEncodingId" -> stringResource(R.string.settings_legacy_encoding)
        "tagWriting.id3v1" -> stringResource(R.string.tag_writing_id3v1)
        "tagWriting.cover" -> stringResource(R.string.tag_writing_cover)
        "sleepTimer.hours" -> stringResource(R.string.timer_hours)
        "sleepTimer.minutes" -> stringResource(R.string.timer_minutes)
        "sleepTimer.finishTrack" -> stringResource(R.string.timer_finish_track)
        "appearance.builtInTheme" -> stringResource(R.string.settings_theme)
        "appearance.activeTheme" -> stringResource(R.string.configuration_active_theme)
        "appearance.nowPlayingMoodBackground" -> stringResource(R.string.settings_now_playing_mood_background)
        else -> themeSettingLabel(change.path, themeNames) ?: change.path
    }
    val summary = remember(change) { configurationLeafChanges(change.before, change.after).take(2).toList() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.testTag("configuration_change_${change.path.replace('.', '_')}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        summary.firstOrNull()?.let { ConfigurationLeafRow(change.path, it, themeNames) }
        if (summary.size > 1) {
            TextButton(
                modifier = Modifier.testTag("configuration_change_details"),
                onClick = { expanded = !expanded },
            ) {
                Text(stringResource(if (expanded) R.string.configuration_hide_details else R.string.configuration_show_details))
            }
            if (expanded) ConfigurationChangeDetails(change, themeNames)
        }
    }
}

@Composable
private fun ConfigurationLeafRow(path: String, change: ConfigurationLeafChange, themeNames: Map<String, String>) {
    val old = change.before as? JsonPrimitive
    val new = change.after as? JsonPrimitive
    val firstDifference = if (old != null && new != null) old.content.commonPrefixWith(new.content).length else 0
    Column {
        if (change.path.isNotEmpty()) Text(change.path, style = MaterialTheme.typography.labelSmall)
        Text(
            stringResource(R.string.configuration_before, configurationValue(path, change.before, themeNames, firstDifference)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.configuration_after, configurationValue(path, change.after, themeNames, firstDifference)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConfigurationChangeDetails(change: ConfigurationChange, themeNames: Map<String, String>) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val details = remember(change, page) {
        configurationLeafChanges(change.before, change.after)
            .drop(1 + page * DETAILS_PAGE_SIZE)
            .take(DETAILS_PAGE_SIZE + 1)
            .toList()
    }
    for (leaf in details.take(DETAILS_PAGE_SIZE)) {
        ConfigurationLeafRow(change.path, leaf, themeNames)
    }
    if (page > 0 || details.size > DETAILS_PAGE_SIZE) {
        Row {
            TextButton(onClick = { page-- }, enabled = page > 0) {
                Text(stringResource(R.string.configuration_previous_details))
            }
            TextButton(onClick = { page++ }, enabled = details.size > DETAILS_PAGE_SIZE) {
                Text(stringResource(R.string.configuration_next_details))
            }
        }
    }
}

private const val DETAILS_PAGE_SIZE = 20

@Composable
private fun themeSettingLabel(path: String, themeNames: Map<String, String>): String? {
    val parts = path.split('.')
    if (parts.size < 4 || parts[0] != "appearance") return null
    val id = parts[2]
    val theme = when (parts[1]) {
        "builtInOverrides" -> stringResource(ThemeMode.fromStored(id).label)
        "savedThemes" -> themeNames[id]?.previewSnippet() ?: id
        else -> return null
    }
    val field = when (parts.last()) {
        "name" -> stringResource(R.string.configuration_theme_name)
        "base" -> stringResource(R.string.configuration_theme_base)
        "presentation" -> stringResource(R.string.settings_now_playing_layout)
        else -> parts.drop(3).joinToString(".")
    }
    return stringResource(R.string.configuration_theme_setting, theme, field)
}

@Composable
private fun configurationValue(
    path: String,
    value: JsonElement?,
    themeNames: Map<String, String>,
    firstDifference: Int,
): String = when (value) {
    null -> stringResource(R.string.configuration_not_set)
    JsonNull -> stringResource(R.string.configuration_none)
    is JsonPrimitive -> configurationScalar(path, value, themeNames, firstDifference)
    else -> value.toString().previewSnippet(firstDifference)
}

@Composable
private fun configurationScalar(
    path: String,
    value: JsonPrimitive,
    themeNames: Map<String, String>,
    firstDifference: Int,
): String {
    val content = value.content
    return when {
        path == "language" -> when (content) {
            "en" -> stringResource(R.string.app_language_english)
            "ru" -> stringResource(R.string.app_language_russian)
            else -> stringResource(R.string.app_language_system)
        }
        path == "library.minLengthSeconds" -> if (content == "0") stringResource(R.string.settings_min_length_off)
            else stringResource(R.string.settings_min_length_value, content.toInt())
        path == "library.legacyEncodingId" && content == "automatic" ->
            stringResource(R.string.settings_legacy_encoding_automatic)
        path == "tagWriting.id3v1" -> Id3v1WritePolicy.entries.firstOrNull { it.name.equals(content, ignoreCase = true) }
            ?.let { stringResource(it.label()) } ?: content
        path == "tagWriting.cover" -> TagCoverPreset.entries.firstOrNull { it.name.equals(content, ignoreCase = true) }
            ?.let { stringResource(it.label()) } ?: content
        path == "appearance.activeTheme" -> if (content == "built_in") stringResource(R.string.configuration_builtin_theme)
            else themeNames[content]?.let { name ->
                stringResource(R.string.configuration_theme_identity, name.previewSnippet(), content)
            } ?: content
        path == "appearance.builtInTheme" || path.startsWith("appearance.savedThemes.") && path.endsWith(".base") ->
            stringResource(ThemeMode.fromStored(content).label)
        path.startsWith("appearance.") && path.endsWith(".presentation") ->
            stringResource(NowPlayingLayout.fromStored(content).label())
        else -> when (value.booleanOrNull?.takeUnless { value.isString }) {
            true -> stringResource(R.string.configuration_on)
            false -> stringResource(R.string.configuration_off)
            null -> content.previewSnippet(firstDifference)
        }
    }
}
