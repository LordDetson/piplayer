package io.github.lorddetson.piplayer.ui.notifications

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.notifications.NotificationPermission
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The notification permission of batches (TI §8, PIM-21): whether the app asks for it at all, and whether the
 * explanation comes before the system dialog.
 */
object BatchNotificationPermission {

    /** POST_NOTIFICATIONS exists from Android 13 (API 33); before it every app may post. */
    fun asks(sdk: Int): Boolean = sdk >= 33

    /** The explanation comes first while the permission is missing and was never asked for (TI §8). */
    fun explains(sdk: Int, granted: Boolean, asked: Boolean): Boolean = asks(sdk) && !granted && !asked
}

/**
 * Whether the explanation came already, in the `device_state` file (docs/library.md §3.2), which is not backed up: a
 * device asks once, and a user who said no is not asked again by a later batch (TI §8).
 */
object BatchNotificationAsked {

    private val ASKED = booleanPreferencesKey("batch_notifications_asked")

    suspend fun isAsked(context: Context): Boolean = SettingsFiles.deviceState(context).data.first()[ASKED] ?: false

    suspend fun markAsked(context: Context) {
        SettingsFiles.edit(SettingsFiles.deviceState(context)) { it[ASKED] = true }
    }

    /** For tests: as before the first batch. */
    suspend fun forget(context: Context) {
        SettingsFiles.edit(SettingsFiles.deviceState(context)) { it.remove(ASKED) }
    }
}

/**
 * What starting a batch does about the notification permission (TI §8, PIM-21): the batch starts at once when the app
 * may post or has asked before, and waits for the explanation otherwise. Whatever the answer, the batch starts: without
 * notifications it works the same and shows its progress in the Fix tab.
 */
@Stable
class BatchNotificationPrompt internal constructor(
    private val sdk: Int,
    private val granted: () -> Boolean,
    private val asked: () -> Boolean,
    private val markAsked: () -> Unit,
    private val ask: () -> Unit,
) {

    /** Whether the explanation is on screen. */
    var explaining by mutableStateOf(false)
        private set

    private var held: (() -> Unit)? = null

    /** Starts a batch through [start], or holds it for the explanation when the permission is to be asked for. */
    fun beforeBatch(start: () -> Unit) {
        if (BatchNotificationPermission.explains(sdk, granted(), asked())) {
            held = start
            explaining = true
        } else {
            start()
        }
    }

    /** "Allow": the system dialog comes up, and its answer starts the held batch. */
    fun allow() {
        explaining = false
        markAsked()
        ask()
    }

    /** Not now: the batch starts without notifications, and the app explains no more. */
    fun notNow() {
        explaining = false
        markAsked()
        release()
    }

    /** The system dialog was answered, granted or not: the held batch starts either way. */
    internal fun answered() = release()

    private fun release() {
        val start = held
        held = null
        start?.invoke()
    }
}

/** Test tags of the explanation of the batch notification's permission (TI §8). */
object BatchNotificationTags {
    const val EXPLANATION = "batch_notification_explanation"
}

/**
 * The batch notification's permission for this launch (TI §8, PIM-21): the explanation, the system dialog behind it, and
 * the flag of the device state that keeps the explanation to the first batch. The flag is read once, before the first
 * batch, as onboarding reads its own.
 */
@Composable
fun rememberBatchNotificationPrompt(): BatchNotificationPrompt {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    lateinit var prompt: BatchNotificationPrompt
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { prompt.answered() }
    val asked = remember { mutableStateOf(runBlocking { BatchNotificationAsked.isAsked(context) }) }
    prompt = remember {
        BatchNotificationPrompt(
            sdk = Build.VERSION.SDK_INT,
            granted = { context.hasNotificationPermission() },
            asked = { asked.value },
            markAsked = {
                asked.value = true
                scope.launch { BatchNotificationAsked.markAsked(context) }
            },
            ask = { launcher.launch(NotificationPermission.name) },
        )
    }
    return prompt
}

/** POST_NOTIFICATIONS on API 33 and later, and nothing to hold before it. */
private fun Context.hasNotificationPermission(): Boolean {
    val permission = NotificationPermission.name
    return permission.isEmpty() ||
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * The explanation before the system dialog (TI §8): what the notification of a batch is for and that the batch runs the
 * same without it. "Allow" brings up the dialog; "Not now" starts the batch anyway.
 */
@Composable
fun BatchNotificationExplanation(prompt: BatchNotificationPrompt) {
    if (!prompt.explaining) return
    AlertDialog(
        onDismissRequest = prompt::notNow,
        // A dialog is a window of its own, outside the activity's test tags as resource IDs.
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(BatchNotificationTags.EXPLANATION),
        title = { Text(stringResource(R.string.batch_notification_explanation_title)) },
        text = { Text(stringResource(R.string.batch_notification_explanation)) },
        confirmButton = {
            TextButton(onClick = prompt::allow) { Text(stringResource(R.string.batch_notification_explanation_allow)) }
        },
        dismissButton = {
            TextButton(onClick = prompt::notNow) { Text(stringResource(R.string.batch_notification_explanation_skip)) }
        },
    )
}
