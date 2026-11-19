package io.github.lorddetson.piplayer.ui.details

import android.app.Activity
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.FRONT_COVER
import io.github.lorddetson.piplayer.tagging.write.TagChange
import io.github.lorddetson.piplayer.tagging.write.TagUndoOutcome
import io.github.lorddetson.piplayer.tagging.write.TagUndoPlan
import io.github.lorddetson.piplayer.tagging.write.TagWriteHistoryEntry
import io.github.lorddetson.piplayer.tagging.write.TagWriteRecovery
import io.github.lorddetson.piplayer.tagging.write.WriteBackResult
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HistoryNotice(val text: Int, val fields: Set<String> = emptySet())

private data class TagHistoryState(
    val entries: List<TagWriteHistoryEntry>? = null,
    val recoveries: List<TagWriteRecovery> = emptyList(),
    val busy: Boolean = false,
    val notice: HistoryNotice? = null,
)

private sealed interface HistoryWrite {
    data class Undo(val plan: TagUndoPlan) : HistoryWrite
    data class Resume(val plan: TagWriteRecovery) : HistoryWrite
}

private class TagHistoryModel(private val identityId: Long, private val writer: BatchTagWriter) : ViewModel() {
    private val mutableState = MutableStateFlow(TagHistoryState())
    val state = mutableState.asStateFlow()
    private var awaitingConsent: HistoryWrite? = null

    fun refreshIfIdle() {
        val shown = mutableState.value
        if (shown.busy) return
        mutableState.value = shown.copy(busy = true)
        viewModelScope.launch { reload(shown.notice) }
    }

    fun undo(changeId: Long, launch: (IntentSenderRequest) -> Unit) =
        request(launch, R.string.tag_history_undo_error) { writer.undoPlan(identityId, changeId)?.let { HistoryWrite.Undo(it) } }

    fun resume(plan: TagWriteRecovery, launch: (IntentSenderRequest) -> Unit) =
        request(launch, R.string.tag_recovery_failed) { HistoryWrite.Resume(plan) }

    private fun request(launch: (IntentSenderRequest) -> Unit, errorMessage: Int, resolve: suspend () -> HistoryWrite?) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val action = resolve()
                if (action == null) {
                    reload(HistoryNotice(R.string.tag_history_unavailable))
                    return@launch
                }
                val sender = withContext(Dispatchers.IO) {
                    when (action) {
                        is HistoryWrite.Undo -> writer.request(action.plan)
                        is HistoryWrite.Resume -> writer.request(action.plan)
                    }
                }
                if (sender == null) {
                    apply(action)
                } else {
                    awaitingConsent = action
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to write tags", error)
                reload(HistoryNotice(errorMessage))
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val action = awaitingConsent ?: return
        awaitingConsent = null
        viewModelScope.launch {
            if (granted) apply(action) else reload(HistoryNotice(when (action) {
                is HistoryWrite.Undo -> R.string.tag_history_cancelled
                is HistoryWrite.Resume -> R.string.manual_tag_cancelled
            }))
        }
    }

    private suspend fun apply(action: HistoryWrite) {
        val notice = try {
            when (action) {
                is HistoryWrite.Undo -> undoNotice(writer.applyUndo(action.plan) { waiting ->
                    showPlaybackWait(waiting, R.string.tag_history_waiting_for_playback)
                })
                is HistoryWrite.Resume -> resumeNotice(writer.resume(action.plan, consent = true) { waiting ->
                    showPlaybackWait(waiting, R.string.manual_tag_waiting_for_playback)
                })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not complete the tag write", error)
            HistoryNotice(if (action is HistoryWrite.Undo) R.string.tag_history_undo_error else R.string.tag_recovery_failed)
        }
        reload(notice)
        try {
            withContext(Dispatchers.IO) { writer.refreshRecoveryNotification() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not update the tag write notification", error)
        }
    }

    private fun showPlaybackWait(waiting: Boolean, message: Int) {
        mutableState.value = mutableState.value.copy(notice = if (waiting) HistoryNotice(message) else null)
    }

    private fun undoNotice(result: TagUndoOutcome): HistoryNotice? = when (result) {
        is TagUndoOutcome.Restored -> HistoryNotice(R.string.tag_history_undone).takeIf { result.conflicts.isEmpty() }
        is TagUndoOutcome.NoChange -> {
            if (result.conflicts.isEmpty()) HistoryNotice(R.string.tag_history_no_change)
            else HistoryNotice(R.string.tag_history_conflicts, result.conflicts)
        }
        TagUndoOutcome.Unavailable -> HistoryNotice(R.string.tag_history_unavailable)
        TagUndoOutcome.Failed -> HistoryNotice(R.string.tag_history_undo_error)
    }

    private fun resumeNotice(result: WriteBackResult): HistoryNotice = HistoryNotice(when (result) {
        WriteBackResult.VERIFIED -> R.string.manual_tag_written
        WriteBackResult.SOURCE_CHANGED -> R.string.manual_tag_changed
        WriteBackResult.NEEDS_CONSENT -> R.string.tag_recovery_consent_required
        else -> R.string.tag_recovery_failed
    })

    private suspend fun reload(notice: HistoryNotice? = null) {
        mutableState.value = try {
            TagHistoryState(writer.historyForTrack(identityId), writer.recoverableWrites(identityId), notice = notice)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the tag history", error)
            TagHistoryState(notice = HistoryNotice(R.string.tag_history_error))
        }
    }

    private companion object {
        const val TAG = "TagHistory"
    }
}

@Composable
private fun TagChangeRows(changes: Map<String, TagChange>, empty: String) {
    for ((field, change) in changes.toSortedMap()) {
        Column {
            Text(field, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(
                R.string.tag_history_transition, change.before.joinToString().ifEmpty { empty },
                change.after.joinToString().ifEmpty { empty },
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagHistoryScreen(identityId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PiPlayerApplication
    val activityOwner = LocalActivity.current as? ViewModelStoreOwner
    val owner = activityOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    val model = viewModel(viewModelStoreOwner = owner, key = "tag-history-$identityId") {
        TagHistoryModel(identityId, app.batchTagWriter)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshIfIdle() }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.consentResult(result.resultCode == Activity.RESULT_OK)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.tag_history_open)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val shown = state
            shown.notice?.let { notice ->
                val message = if (notice.fields.isEmpty()) stringResource(notice.text)
                else stringResource(notice.text, notice.fields.sorted().joinToString())
                Text(message, Modifier.padding(16.dp))
            }
            if (shown.busy || (shown.entries == null && shown.notice == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            shown.entries?.let { entries ->
                TagHistoryContent(
                    entries, Modifier.weight(1f), onUndo = { id -> model.undo(id) { consent.launch(it) } },
                    busy = shown.busy, recoveries = shown.recoveries,
                    onResume = { plan -> model.resume(plan) { consent.launch(it) } },
                )
            }
        }
    }
}

@Composable
internal fun TagHistoryContent(
    entries: List<TagWriteHistoryEntry>,
    modifier: Modifier = Modifier,
    onUndo: ((Long) -> Unit)? = null,
    busy: Boolean = false,
    recoveries: List<TagWriteRecovery> = emptyList(),
    onResume: ((TagWriteRecovery) -> Unit)? = null,
) {
    if (entries.isEmpty() && recoveries.isEmpty()) {
        Text(stringResource(R.string.tag_history_empty), modifier.padding(16.dp))
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val empty = stringResource(R.string.tag_history_empty_value)
    LazyColumn(modifier.fillMaxSize()) {
        items(recoveries, key = { it.id }) { recovery ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recovery.target.displayName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (recovery.failed) R.string.tag_recovery_failed else R.string.tag_recovery_interrupted))
                TagChangeRows(recovery.changes, empty)
                if (onResume != null) {
                    TextButton(onClick = { onResume(recovery) }, enabled = !busy) {
                        Text(stringResource(R.string.tag_recovery_resume))
                    }
                }
            }
            HorizontalDivider()
        }
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                Text(date.format(Date(entry.at)), style = MaterialTheme.typography.labelMedium)
                TagChangeRows(entry.changes, empty)
                entry.coverChange?.let { change ->
                    Text(stringResource(R.string.tag_history_cover_count, change.before.size, change.after.size))
                }
                entry.legacyId3Change?.let { change ->
                    Text(stringResource(when {
                        change.after == null -> R.string.tag_history_id3v1_removed
                        change.before == null -> R.string.tag_history_id3v1_restored
                        else -> R.string.tag_history_id3v1_updated
                    }))
                }
                if (entry.conflicts.isNotEmpty()) {
                    val cover = stringResource(R.string.tag_history_front_cover)
                    val fields = entry.conflicts.sorted().joinToString { if (it == FRONT_COVER) cover else it }
                    Text(stringResource(R.string.tag_history_conflicts, fields))
                }
                if (entry.undoneAt != null) {
                    Text(stringResource(R.string.tag_history_undone), style = MaterialTheme.typography.labelMedium)
                } else if (onUndo != null) {
                    TextButton(onClick = { onUndo(entry.id) }, enabled = !busy) { Text(stringResource(R.string.tag_history_undo)) }
                }
            }
            HorizontalDivider()
        }
    }
}
