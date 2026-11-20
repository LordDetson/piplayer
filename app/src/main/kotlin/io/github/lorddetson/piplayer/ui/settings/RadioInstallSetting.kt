package io.github.lorddetson.piplayer.ui.settings

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import io.github.lorddetson.piplayer.R

internal const val RADIO_ACTIVITY = "io.github.lorddetson.piplayer.radio.RadioActivity"

internal class RadioInstallViewModel(application: Application) : AndroidViewModel(application) {
    val installer = PlatformRadioInstaller(application)
    val controller = RadioInstallController(installer)

    override fun onCleared() = installer.close()
}

@Composable
internal fun RadioInstallSetting(viewModel: RadioInstallViewModel = viewModel()) {
    if (!viewModel.installer.entryAvailable) return
    val context = LocalContext.current
    val controller = viewModel.controller
    val play = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) controller.cancel()
    }
    RadioInstallEntry(
        controller,
        onOpenRadio = {
            try {
                context.startActivity(Intent().setClassName(context.packageName, RADIO_ACTIVITY))
            } catch (_: ActivityNotFoundException) {
                controller.launchFailed()
            }
        },
        onStoreConfirmation = { viewModel.installer.showStoreConfirmation(play) },
    )
}

@Composable
internal fun RadioInstallEntry(
    controller: RadioInstallController,
    onOpenRadio: () -> Unit,
    onStoreConfirmation: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    TextRow(
        stringResource(R.string.radio_install_entry),
        stringResource(R.string.radio_install_summary),
        controller::open,
    )
    when (val current = state) {
        RadioInstallState.Idle -> Unit
        RadioInstallState.Offer -> AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text(stringResource(R.string.radio_install_question)) },
            text = { Text(stringResource(R.string.radio_install_explanation)) },
            confirmButton = {
                TextButton(onClick = controller::install) { Text(stringResource(R.string.radio_install_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = controller::dismiss) { Text(stringResource(R.string.radio_install_later)) }
            },
        )
        is RadioInstallState.Downloading -> AlertDialog(
            onDismissRequest = controller::cancel,
            title = { Text(stringResource(R.string.radio_install_progress)) },
            text = {
                val percent = current.percent
                if (percent == null) LinearProgressIndicator()
                else LinearProgressIndicator(progress = { percent / 100f })
            },
            confirmButton = {
                if (current.canCancel) {
                    TextButton(onClick = controller::cancel) { Text(stringResource(R.string.settings_dialog_cancel)) }
                }
            },
        )
        RadioInstallState.Failed -> AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text(stringResource(R.string.radio_install_failed)) },
            text = { Text(stringResource(R.string.radio_install_failed_detail)) },
            confirmButton = {
                TextButton(onClick = controller::install) { Text(stringResource(R.string.radio_install_retry)) }
            },
            dismissButton = {
                TextButton(onClick = controller::dismiss) { Text(stringResource(R.string.radio_install_later)) }
            },
        )
        RadioInstallState.OpenRadio -> LaunchedEffect(controller, lifecycle) {
            lifecycle.withResumed {
                if (controller.consumeOpen()) onOpenRadio()
            }
        }
        RadioInstallState.StoreConfirmation -> LaunchedEffect(controller, lifecycle) {
            lifecycle.withResumed {
                if (controller.state.value == RadioInstallState.StoreConfirmation) onStoreConfirmation()
            }
        }
    }
}
