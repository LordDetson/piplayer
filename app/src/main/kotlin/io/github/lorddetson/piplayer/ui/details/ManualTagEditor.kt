package io.github.lorddetson.piplayer.ui.details

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.contract.IdentificationTarget
import io.github.lorddetson.piplayer.library.values.LegacyEncodings
import io.github.lorddetson.piplayer.nativelib.taglib.Id3v1WritePolicy
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.ManualTagPlan
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagDiff
import io.github.lorddetson.piplayer.tagging.write.TagFileFormat
import io.github.lorddetson.piplayer.tagging.write.WriteBackResult
import io.github.lorddetson.piplayer.tagging.write.manualPiPlayerEdit
import io.github.lorddetson.piplayer.ui.identification.ReviewDecisions
import io.github.lorddetson.piplayer.ui.identification.ReviewEdit
import io.github.lorddetson.piplayer.ui.settings.LegacyEncodingChoice
import io.github.lorddetson.piplayer.userdata.TrackLegacyEncoding
import io.github.lorddetson.piplayer.userdata.followsLibrary
import io.github.lorddetson.piplayer.userdata.overrideEncoding
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EditorState(
    val plan: ManualTagPlan? = null,
    val preview: EditorPreview? = null,
    val busy: Boolean = true,
    val message: Int? = null,
    val savedEdit: Long? = null,
    val waitingForPlayback: Boolean = false,
)

private data class EditorPreview(val changes: Map<String, TagChange>, val id3v1KeptFields: Set<String>? = null)

private data class EditorSubmission(
    val plan: ManualTagPlan,
    val changes: Map<String, List<String>>,
)

private class ManualTagEditorModel(
    private val fileId: Long,
    private val writer: BatchTagWriter,
    private val decisions: ReviewDecisions,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorState())
    val state = mutableState.asStateFlow()
    private var awaitingConsent: EditorSubmission? = null

    init {
        viewModelScope.launch { reload() }
    }

    fun refreshIfIdle() {
        val current = mutableState.value
        if (!current.busy && current.preview == null) {
            mutableState.value = current.copy(busy = true)
            viewModelScope.launch { reload(current.message, current.savedEdit) }
        }
    }

    fun preview(changes: Map<String, List<String>>, fillEmptyOnly: Set<String>) {
        val current = mutableState.value
        val plan = current.plan ?: return
        if (current.busy) return
        val diff = TagDiff.between(plan.displayProperties, changes, fillEmptyOnly)
        if (diff.changes.isEmpty() && !plan.removesId3v1) return
        if (plan.legacyId3 == null || plan.options.id3v1 != Id3v1WritePolicy.UPDATE_IF_PRESENT) {
            mutableState.value = current.copy(preview = EditorPreview(diff.changes), message = null)
            return
        }
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            val keptFields = try {
                writer.previewId3v1(plan, diff.changes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not preview ID3v1 changes", error)
                null
            }
            mutableState.value = current.copy(preview = EditorPreview(diff.changes, keptFields), message = null)
        }
    }

    fun chooseEncoding(name: String?) {
        val current = mutableState.value
        val plan = current.plan ?: return
        if (current.busy) return
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            storeDecision {
                val updated = writer.chooseManualEncoding(plan, name)
                if (updated == null) finish(R.string.manual_tag_changed)
                else mutableState.value = current.copy(plan = updated, message = null)
            }
        }
    }

    fun closePreview(): Boolean {
        val current = mutableState.value
        if (current.preview == null) return false
        if (!current.busy) mutableState.value = current.copy(preview = null, message = null)
        return true
    }

    fun submit(launch: (IntentSenderRequest) -> Unit) {
        val current = mutableState.value
        val plan = current.plan ?: return
        val diff = current.preview?.changes ?: return
        if (current.busy) return
        mutableState.value = current.copy(busy = true, message = null)
        val submission = EditorSubmission(plan, diff.mapValues { it.value.after })
        viewModelScope.launch {
            try {
                val sender = withContext(Dispatchers.IO) { writer.request(plan) }
                if (sender == null) {
                    apply(submission)
                } else {
                    awaitingConsent = submission
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to edit file tags", error)
                finish(R.string.manual_tag_failed)
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val submission = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) viewModelScope.launch { apply(submission) } else finish(R.string.manual_tag_cancelled)
    }

    fun saveInPiPlayer() {
        val current = mutableState.value
        val plan = current.plan ?: return
        val changes = current.preview?.changes ?: return
        if (current.busy) return
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            storeDecision {
                val live = writer.manualPlan(fileId, forEditor = true)
                if (live == null || live.target != plan.target || changes.any { (key, change) ->
                        live.properties[key] != plan.properties[key] || live.displayProperties[key].orEmpty() != change.before
                    }) {
                    finish(R.string.manual_tag_changed)
                    return@storeDecision
                }
                val values = manualPiPlayerEdit(live.displayProperties, changes)
                val target = IdentificationTarget.LibraryFile(fileId, live.target.contentKey)
                val saved = values?.let { decisions.applyWithEdit(null, null, ReviewEdit(target, it, null, null)) }
                if (saved == null) finish(R.string.manual_tag_save_failed)
                else reload(R.string.manual_tag_saved_in_player, saved.decisionId)
            }
        }
    }

    fun undoSavedEdit() {
        val current = mutableState.value
        val id = current.savedEdit ?: return
        if (current.busy) return
        mutableState.value = current.copy(busy = true, message = null)
        viewModelScope.launch {
            storeDecision {
                if (decisions.undo(id)) reload(R.string.review_undone)
                else finish(R.string.review_undo_failed)
            }
        }
    }

    private suspend fun storeDecision(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not store the manual tag decision", error)
            finish(R.string.manual_tag_save_failed)
        }
    }

    private suspend fun apply(submission: EditorSubmission) {
        try {
            val result = writer.applyManual(submission.plan, submission.changes) { waiting ->
                mutableState.value = mutableState.value.copy(
                    waitingForPlayback = waiting,
                    message = R.string.manual_tag_waiting_for_playback.takeIf { waiting },
                )
            }
            when (result) {
                WriteBackResult.VERIFIED -> reload(R.string.manual_tag_written)
                WriteBackResult.SOURCE_CHANGED -> finish(R.string.manual_tag_changed)
                else -> finish(R.string.manual_tag_failed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not write manually edited file tags", error)
            finish(R.string.manual_tag_failed)
        }
    }

    private suspend fun reload(message: Int? = null, savedEdit: Long? = null) {
        val plan = try {
            writer.manualPlan(fileId, forEditor = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read current file tags", error)
            null
        }
        mutableState.value = EditorState(
            plan, busy = false, message = message ?: R.string.manual_tag_unavailable.takeIf { plan == null }, savedEdit = savedEdit,
        )
    }

    private fun finish(message: Int) {
        mutableState.value = mutableState.value.copy(busy = false, message = message)
    }

    private companion object {
        const val TAG = "ManualTagEditor"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualTagEditorScreen(fileId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PiPlayerApplication
    val activityOwner = LocalActivity.current as? ViewModelStoreOwner
    val owner = activityOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    val model = viewModel(viewModelStoreOwner = owner, key = "manual-tag-editor-$fileId") {
        ManualTagEditorModel(fileId, app.batchTagWriter, ReviewDecisions.of(app))
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshIfIdle() }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.consentResult(result.resultCode == Activity.RESULT_OK)
    }
    BackHandler(enabled = state.preview != null && !state.waitingForPlayback) { model.closePreview() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.manual_tag_open)) },
            navigationIcon = {
                IconButton(onClick = { if (state.waitingForPlayback || !model.closePreview()) onBack() }) {
                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back))
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val shown = state
            if (shown.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            shown.message?.let { Text(stringResource(it), Modifier.padding(16.dp)) }
            if (shown.savedEdit != null && shown.preview == null) {
                TextButton(onClick = model::undoSavedEdit, enabled = !shown.busy) { Text(stringResource(R.string.review_undo)) }
            }
            shown.plan?.let { plan ->
                val draft = remember(plan.target, plan.properties, shown.savedEdit) { ManualTagDraft() }
                if (shown.preview == null) {
                    plan.legacyDisplay?.let { legacy ->
                        LegacyEncodingChoice(
                            selected = legacy.choice.overrideEncoding(),
                            available = remember { LegacyEncodings.available() },
                            followsLibrary = legacy.choice.followsLibrary(),
                            onFollowLibrary = { model.chooseEncoding(TrackLegacyEncoding.FOLLOW_LIBRARY) },
                            enabled = !shown.busy,
                        ) { model.chooseEncoding(it?.name) }
                    }
                    ManualTagForm(
                        plan.displayProperties, modifier = Modifier.weight(1f), busy = shown.busy,
                        draft = draft, actionLabel = R.string.manual_tag_review, canRemoveLegacyTag = plan.removesId3v1,
                        onSubmit = model::preview,
                    )
                } else {
                    val canSave = remember(plan, shown.preview) {
                        manualPiPlayerEdit(plan.displayProperties, shown.preview.changes) != null
                    }
                    ManualTagPreview(
                        shown.preview.changes, plan.format, shown.busy, onBack = { model.closePreview() },
                        onSave = (model::saveInPiPlayer).takeIf { canSave },
                        legacyId3Policy = plan.options.id3v1.takeIf { plan.legacyId3 != null },
                        id3v1KeptFields = shown.preview.id3v1KeptFields,
                    ) { model.submit { consent.launch(it) } }
                }
            }
        }
    }
}

private data class ManualField(val key: String, val label: Int? = null, val multiple: Boolean = false)

private val mainFields = listOf(
    ManualField("TITLE", R.string.details_field_title),
    ManualField("ARTIST", R.string.details_field_artist, multiple = true),
    ManualField("ALBUM", R.string.details_field_album),
    ManualField("ALBUMARTIST", R.string.details_field_album_artist, multiple = true),
    ManualField("DATE", R.string.details_field_date),
    ManualField("TRACKNUMBER", R.string.details_field_track),
    ManualField("DISCNUMBER", R.string.details_field_disc),
    ManualField("COMPOSER", R.string.details_field_composer, multiple = true),
    ManualField("GENRE", R.string.details_field_genre, multiple = true),
    ManualField("COMMENT", R.string.manual_tag_comment),
    ManualField("LYRICS", R.string.manual_tag_lyrics),
)
private val keepExisting = setOf("GENRE", "COMMENT")

internal class ManualTagDraft {
    val edits = mutableStateMapOf<String, String>()
    var fillOnly by mutableStateOf(keepExisting)
}

@Composable
internal fun ManualTagPreview(
    changes: Map<String, TagChange>,
    format: TagFileFormat,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (() -> Unit)? = null,
    legacyId3Policy: Id3v1WritePolicy? = null,
    id3v1KeptFields: Set<String>? = null,
    onWrite: () -> Unit,
) {
    val empty = stringResource(R.string.tag_history_empty_value)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(changes.entries.toList(), key = { it.key }) { (key, change) ->
                val label = mainFields.firstOrNull { it.key == key }?.label
                Column {
                    Text(label?.let { stringResource(it) } ?: key)
                    Text(stringResource(
                        R.string.tag_history_transition,
                        change.before.joinToString().ifEmpty { empty },
                        change.after.joinToString().ifEmpty { empty },
                    ))
                }
            }
            if (legacyId3Policy != null) item {
                Column {
                    Text(stringResource(R.string.tag_writing_id3v1))
                    Text(stringResource(when (legacyId3Policy) {
                        Id3v1WritePolicy.UPDATE_IF_PRESENT -> R.string.tag_writing_id3v1_preview_update
                        Id3v1WritePolicy.REMOVE -> R.string.tag_writing_id3v1_preview_remove
                        Id3v1WritePolicy.LEAVE_UNTOUCHED -> R.string.tag_writing_id3v1_preview_keep
                    }))
                    if (legacyId3Policy == Id3v1WritePolicy.UPDATE_IF_PRESENT) {
                        if (id3v1KeptFields == null) Text(stringResource(R.string.tag_writing_id3v1_preview_unavailable))
                        for (key in id3v1KeptFields.orEmpty()) {
                            val label = mainFields.firstOrNull { it.key == key }?.label
                            Text(
                                stringResource(R.string.tag_writing_id3v1_preview_kept_field, label?.let { stringResource(it) } ?: key),
                                modifier = Modifier.testTag("manual-id3v1-kept-$key"),
                            )
                        }
                    }
                }
            }
        }
        if (onSave != null) {
            Button(onClick = onSave, enabled = !busy, modifier = Modifier.testTag("manual-tag-save-in-player")) {
                Text(stringResource(R.string.manual_tag_save_in_player))
            }
        } else {
            Text(stringResource(R.string.manual_tag_file_only))
        }
        if (format != TagFileFormat.UNSUPPORTED) {
            if (format == TagFileFormat.WAV) Text(stringResource(R.string.manual_tag_wav_notice))
            Text(stringResource(R.string.manual_tag_access))
            TextButton(onClick = onWrite, enabled = !busy) { Text(stringResource(R.string.manual_tag_write)) }
        }
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.manual_tag_continue_editing)) }
    }
}

@Composable
internal fun ManualTagForm(
    initial: Map<String, List<String>>,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    draft: ManualTagDraft = remember(initial) { ManualTagDraft() },
    actionLabel: Int = R.string.manual_tag_write,
    canRemoveLegacyTag: Boolean = false,
    onSubmit: (Map<String, List<String>>, Set<String>) -> Unit,
) {
    val fields = remember(initial) {
        val known = mainFields.mapTo(HashSet()) { it.key }
        mainFields + initial.keys.filter { it !in known }.sorted().map { key -> ManualField(key, multiple = initial.getValue(key).size > 1) }
    }
    val edits = draft.edits
    val fillOnly = draft.fillOnly
    val canWrite = edits.any { (key, value) ->
        value != initial[key].orEmpty().joinToString("\n") && (key !in fillOnly || initial[key].orEmpty().all(String::isEmpty))
    }
    Column(modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(fields, key = { it.key }) { field ->
                val key = field.key
                val label = field.label?.let { stringResource(it) } ?: key
                val before = initial[key].orEmpty().joinToString("\n")
                Column {
                    OutlinedTextField(
                        value = edits[key] ?: before,
                        onValueChange = { edits[key] = it },
                        label = { Text(label) },
                        enabled = !busy,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("manual-tag-$key"),
                    )
                    if (key in keepExisting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in fillOnly, onCheckedChange = { checked ->
                                draft.fillOnly = if (checked) fillOnly + key else fillOnly - key
                            }, enabled = !busy)
                            Text(stringResource(R.string.manual_tag_fill_empty, label))
                        }
                    }
                }
            }
        }
        Button(enabled = (canWrite || canRemoveLegacyTag) && !busy, onClick = {
            val changes = buildMap {
                for (field in fields) {
                    val text = edits[field.key] ?: continue
                    if (text == initial[field.key].orEmpty().joinToString("\n")) continue
                    val values = if (field.multiple) text.lines().filter(String::isNotEmpty) else listOfNotNull(text.takeIf(String::isNotEmpty))
                    put(field.key, values)
                }
            }
            onSubmit(changes, fillOnly)
        }) { Text(stringResource(actionLabel)) }
    }
}
