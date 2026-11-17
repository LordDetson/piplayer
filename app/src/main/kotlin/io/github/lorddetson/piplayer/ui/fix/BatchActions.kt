package io.github.lorddetson.piplayer.ui.fix

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.identification.entry.BatchFiles
import io.github.lorddetson.piplayer.identification.entry.BatchLauncher
import io.github.lorddetson.piplayer.identification.entry.BatchRequest
import io.github.lorddetson.piplayer.identification.entry.RoomBatchLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Identify" over many songs (TI §1 "Entry points"): an entry point sends the [BatchRequest] of what the user chose, and
 * the app reads its files, starts the batch and says how it went. Null where the app cannot start batches, which leaves
 * the entries that offer it out.
 */
class BatchActions(val identify: (BatchRequest) -> Unit)

/**
 * The app's [BatchActions] (TI §1): the files of the request are read from the library, the batch is started through
 * [RoomBatchLauncher], and a snackbar says the batch is queued or that there was nothing to identify.
 */
@Composable
fun rememberBatchActions(snackbar: SnackbarHostState): BatchActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { BatchFiles(context) }
    val launcher: BatchLauncher = remember(context) { RoomBatchLauncher(context) }
    return remember(snackbar, resources, files, launcher) {
        BatchActions { request ->
            scope.launch {
                val ids = try {
                    files.of(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    snackbar.showSnackbar(resources.getString(R.string.batch_failed))
                    return@launch
                }
                if (ids.isEmpty()) {
                    snackbar.showSnackbar(resources.getString(R.string.batch_none))
                    return@launch
                }
                try {
                    launcher.start(request.source, ids)
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.batch_started, ids.size, ids.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    snackbar.showSnackbar(resources.getString(R.string.batch_failed))
                }
            }
        }
    }
}
