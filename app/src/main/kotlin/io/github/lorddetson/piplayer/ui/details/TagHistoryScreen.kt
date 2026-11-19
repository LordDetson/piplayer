package io.github.lorddetson.piplayer.ui.details

import android.app.Activity
import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.TagUndoOutcome
import io.github.lorddetson.piplayer.tagging.write.TagUndoPlan
import io.github.lorddetson.piplayer.tagging.write.TagWriteHistoryEntry
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
    val busy: Boolean = false,
    val notice: HistoryNotice? = null,
)

private class TagHistoryModel(private val identityId: Long, private val writer: BatchTagWriter) : ViewModel() {
    private val mutableState = MutableStateFlow(TagHistoryState())
    val state = mutableState.asStateFlow()
    private var awaitingConsent: TagUndoPlan? = null

    init {
        viewModelScope.launch { reload() }
    }

    fun undo(changeId: Long, launch: (IntentSenderRequest) -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val plan = writer.undoPlan(identityId, changeId)
                if (plan == null) {
                    reload(HistoryNotice(R.string.tag_history_unavailable))
                    return@launch
                }
                val sender = withContext(Dispatchers.IO) { writer.request(plan) }
                if (sender == null) {
                    apply(plan)
                } else {
                    awaitingConsent = plan
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to undo a tag write", error)
                reload(HistoryNotice(R.string.tag_history_undo_error))
            }
        }
    }

    fun consentResult(granted: Boolean) {
        val plan = awaitingConsent ?: return
        awaitingConsent = null
        viewModelScope.launch {
            if (granted) apply(plan) else reload(HistoryNotice(R.string.tag_history_cancelled))
        }
    }

    private suspend fun apply(plan: TagUndoPlan) {
        val notice = try {
            when (val result = writer.applyUndo(plan)) {
                is TagUndoOutcome.Restored -> if (result.conflicts.isEmpty()) HistoryNotice(R.string.tag_history_undone) else null
                is TagUndoOutcome.NoChange -> {
                    if (result.conflicts.isEmpty()) HistoryNotice(R.string.tag_history_no_change)
                    else HistoryNotice(R.string.tag_history_conflicts, result.conflicts)
                }
                TagUndoOutcome.Deferred -> HistoryNotice(R.string.tag_history_deferred)
                TagUndoOutcome.Unavailable -> HistoryNotice(R.string.tag_history_unavailable)
                TagUndoOutcome.Failed -> HistoryNotice(R.string.tag_history_undo_error)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not undo a tag write", error)
            HistoryNotice(R.string.tag_history_undo_error)
        }
        reload(notice)
    }

    private suspend fun reload(notice: HistoryNotice? = null) {
        mutableState.value = try {
            TagHistoryState(writer.historyForTrack(identityId), notice = notice)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagHistoryScreen(identityId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PiPlayerApplication
    val model = viewModel(key = "tag-history-$identityId") { TagHistoryModel(identityId, app.batchTagWriter) }
    val state by model.state.collectAsStateWithLifecycle()
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
                TagHistoryContent(entries, Modifier.weight(1f), onUndo = { id -> model.undo(id) { consent.launch(it) } }, busy = shown.busy)
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
) {
    if (entries.isEmpty()) {
        Text(stringResource(R.string.tag_history_empty), modifier.padding(16.dp))
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    val empty = stringResource(R.string.tag_history_empty_value)
    LazyColumn(modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                Text(date.format(Date(entry.at)), style = MaterialTheme.typography.labelMedium)
                for ((field, change) in entry.changes.toSortedMap()) {
                    Column {
                        Text(field, style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(
                            R.string.tag_history_transition, change.before.joinToString().ifEmpty { empty },
                            change.after.joinToString().ifEmpty { empty },
                        ))
                    }
                }
                if (entry.conflicts.isNotEmpty()) {
                    Text(stringResource(R.string.tag_history_conflicts, entry.conflicts.sorted().joinToString()))
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
