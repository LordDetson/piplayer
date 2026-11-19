package io.github.lorddetson.piplayer.ui.fix

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import io.github.lorddetson.piplayer.tagging.write.BatchTagApply
import io.github.lorddetson.piplayer.tagging.write.BatchTagUndoGroup
import io.github.lorddetson.piplayer.tagging.write.BatchTagWriter
import io.github.lorddetson.piplayer.tagging.write.TagApplyGroup
import io.github.lorddetson.piplayer.tagging.write.TagUndoOutcome
import io.github.lorddetson.piplayer.tagging.write.TagWriteHistoryEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface BatchWriteState {
    data object Loading : BatchWriteState
    data object Error : BatchWriteState

    data class Ready(
        val group: TagApplyGroup,
        val undo: BatchTagUndoGroup,
        val results: Map<String, Int>,
        val history: List<TagWriteHistoryEntry>,
        val busy: Boolean = false,
        val waitingForPlayback: Boolean = false,
        val message: Int? = null,
    ) : BatchWriteState
}

private enum class FileTagAction { WRITE, UNDO }

@Composable
internal fun BatchTagUndoSummary(
    history: List<TagWriteHistoryEntry>,
    eligible: Int,
    busy: Boolean,
    attemptConflicts: List<Pair<String, Set<String>>> = emptyList(),
    onUndo: () -> Unit,
) {
    val writes = history.filter { it.undoOf == null }
    if (writes.isEmpty()) return
    Text(stringResource(R.string.batch_tag_undo_counts, writes.size, writes.count { it.undoneAt != null }))
    val conflicts = history.filter { it.conflicts.isNotEmpty() }.map { it.displayName to it.conflicts } + attemptConflicts
    for ((file, keys) in conflicts.groupBy({ it.first }, { it.second })) {
        Text(stringResource(R.string.batch_tag_undo_conflict, file, keys.flatten().toSortedSet().joinToString()))
    }
    if (eligible > 0) {
        Text(stringResource(R.string.batch_tag_undo_explanation, eligible))
        Button(onClick = onUndo, enabled = !busy) { Text(stringResource(R.string.batch_tag_undo_start)) }
    }
}

private class BatchTagWriteModel(
    private val batch: String,
    private val writer: BatchTagWriter,
    private val batchApply: BatchTagApply,
) : ViewModel() {
    private val mutableState = MutableStateFlow<BatchWriteState>(BatchWriteState.Loading)
    val state = mutableState.asStateFlow()
    private var awaitingConsent: Pair<BatchWriteState.Ready, FileTagAction>? = null
    private val undoOutcomes = LinkedHashMap<Long, Pair<String, TagUndoOutcome>>()

    init {
        viewModelScope.launch {
            read()
            batchApply.current(batch)?.let { follow(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch { read() }
    }

    fun retryUndo() {
        undoOutcomes.clear()
        refresh()
    }

    fun recentConflicts(): List<Pair<String, Set<String>>> = undoOutcomes.values.mapNotNull { (file, outcome) ->
        val conflicts = when (outcome) {
            is TagUndoOutcome.Restored -> outcome.conflicts
            is TagUndoOutcome.NoChange -> outcome.conflicts
            else -> emptySet()
        }
        (file to conflicts).takeIf { conflicts.isNotEmpty() }
    }

    fun incompleteUndos(): Int = undoOutcomes.values.count { (_, outcome) ->
        outcome == TagUndoOutcome.Failed || outcome == TagUndoOutcome.Unavailable
    }

    fun requestConsent(activity: Context, launch: (IntentSenderRequest) -> Unit) {
        request(activity, FileTagAction.WRITE, launch)
    }

    fun requestUndoConsent(activity: Context, launch: (IntentSenderRequest) -> Unit) {
        request(activity, FileTagAction.UNDO, launch)
    }

    private fun request(activity: Context, action: FileTagAction, launch: (IntentSenderRequest) -> Unit) {
        val shown = mutableState.value as? BatchWriteState.Ready ?: return
        val hasFiles = when (action) {
            FileTagAction.WRITE -> shown.group.files.isNotEmpty()
            FileTagAction.UNDO -> shown.undo.files.isNotEmpty()
        }
        if (shown.busy || !hasFiles) return
        mutableState.value = shown.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val sender = withContext(Dispatchers.IO) {
                    when (action) {
                        FileTagAction.WRITE -> writer.request(shown.group)
                        FileTagAction.UNDO -> writer.request(shown.undo)
                    }
                }
                if (sender == null) {
                    apply(activity, shown, action)
                } else {
                    awaitingConsent = shown to action
                    launch(IntentSenderRequest.Builder(sender).build())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                awaitingConsent = null
                Log.w(TAG, "Could not request permission to write tags", error)
                mutableState.value = shown.copy(message = errorMessage(action))
            }
        }
    }

    fun consentResult(activity: Context, granted: Boolean) {
        val (shown, action) = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) {
            viewModelScope.launch { apply(activity, shown, action) }
        } else {
            mutableState.value = shown.copy(message = when (action) {
                FileTagAction.WRITE -> R.string.batch_tag_write_cancelled
                FileTagAction.UNDO -> R.string.batch_tag_undo_cancelled
            })
        }
    }

    private suspend fun apply(activity: Context, shown: BatchWriteState.Ready, action: FileTagAction) {
        try {
            when (action) {
                FileTagAction.WRITE -> {
                    follow(batchApply.start(activity, shown.group))
                    return
                }
                FileTagAction.UNDO -> {
                    val results = writer.undo(shown.undo, ::showPlaybackWait)
                    for (file in shown.undo.files) {
                        results[file.changeId]?.let { undoOutcomes[file.changeId] = file.displayName to it }
                    }
                }
            }
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not write tags", error)
            read(errorMessage(action))
        }
    }

    private suspend fun follow(run: BatchTagApply.Run) {
        val ready = mutableState.value as? BatchWriteState.Ready
        if (ready != null) mutableState.value = ready.copy(busy = true, message = null)
        val outcome = coroutineScope {
            val waiting = launch { run.waitingForPlayback.collect(::showPlaybackWait) }
            try {
                run.outcome.await()
            } finally {
                waiting.cancel()
            }
        }
        read(when (outcome) {
            BatchTagApply.Outcome.FINISHED -> null
            BatchTagApply.Outcome.PAUSED -> R.string.batch_tag_write_paused
            BatchTagApply.Outcome.FAILED -> R.string.batch_tag_write_error
        })
    }

    private fun showPlaybackWait(waiting: Boolean) {
        val state = mutableState.value as? BatchWriteState.Ready ?: return
        mutableState.value = state.copy(waitingForPlayback = waiting)
    }

    private suspend fun read(message: Int? = null) {
        mutableState.value = try {
            BatchWriteState.Ready(
                writer.nextGroup(batch), writer.nextUndoGroup(batch, undoOutcomes.keys), writer.results(batch),
                writer.historyForBatch(batch), message = message,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the files to write", error)
            BatchWriteState.Error
        }
    }

    private fun errorMessage(action: FileTagAction): Int = when (action) {
        FileTagAction.WRITE -> R.string.batch_tag_write_error
        FileTagAction.UNDO -> R.string.tag_history_undo_error
    }

    private companion object {
        const val TAG = "BatchTagWrite"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchTagWriteScreen(batchUuid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val launchContext = activity ?: context
    val app = context.applicationContext as PiPlayerApplication
    // The Activity owns the grant and the apply continues when the user opens Now Playing.
    val activityOwner = activity as? ViewModelStoreOwner
    val owner = activityOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
    val model = viewModel(viewModelStoreOwner = owner, key = "batch-tag-write-$batchUuid") {
        BatchTagWriteModel(batchUuid, app.batchTagWriter, app.batchTagApply)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        model.consentResult(launchContext, result.resultCode == Activity.RESULT_OK)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.batch_tag_write_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.library_back)) }
            },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val shown = state) {
                BatchWriteState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                BatchWriteState.Error -> {
                    Text(stringResource(R.string.batch_tag_write_error))
                    Button(onClick = model::refresh) { Text(stringResource(R.string.review_try_again)) }
                }
                is BatchWriteState.Ready -> {
                    if (shown.results.isEmpty()) Text(stringResource(R.string.batch_tag_write_empty))
                    else if (shown.history.isEmpty() || shown.results.any { (key, count) -> key != "written" && count > 0 }) Text(stringResource(
                        R.string.batch_tag_write_counts, shown.results["written"] ?: 0, shown.results["queued"] ?: 0,
                        shown.results["skipped"] ?: 0, shown.results["failed"] ?: 0,
                    ))
                    shown.message?.let { Text(stringResource(it)) }
                    if (shown.waitingForPlayback) {
                        Text(stringResource(R.string.batch_tag_write_waiting))
                    } else if (shown.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (shown.group.files.isNotEmpty()) {
                        Text(stringResource(R.string.batch_tag_write_explanation, shown.group.files.size))
                        Button(onClick = { model.requestConsent(launchContext) { consent.launch(it) } }) {
                            Text(stringResource(R.string.batch_tag_write_start))
                        }
                    } else if ((shown.results["queued"] ?: 0) > 0) {
                        Text(stringResource(R.string.batch_tag_write_empty))
                        Button(onClick = model::refresh) { Text(stringResource(R.string.review_try_again)) }
                    }
                    BatchTagUndoSummary(
                        shown.history, shown.undo.files.size, shown.busy, model.recentConflicts(),
                    ) {
                        model.requestUndoConsent(launchContext) { consent.launch(it) }
                    }
                    val incomplete = model.incompleteUndos()
                    if (incomplete > 0) {
                        Text(stringResource(R.string.batch_tag_undo_incomplete, incomplete))
                        if (shown.undo.files.isEmpty() && !shown.busy) {
                            Button(onClick = model::retryUndo) { Text(stringResource(R.string.review_try_again)) }
                        }
                    }
                }
            }
        }
    }
}
