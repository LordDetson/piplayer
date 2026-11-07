package io.github.lorddetson.piplayer.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The first launch's single screen (docs/library.md §14.1). */
object Onboarding {

    /** In the `device_state` file (docs/library.md §3.2), which is not backed up. */
    private val DONE = booleanPreferencesKey("onboarding_done")

    /** Whether the app shows onboarding: without the permission ([granted]) and before it is [done]. */
    fun shows(granted: Boolean, done: Boolean): Boolean = !granted && !done

    /** Whether onboarding is done on this device. */
    suspend fun isDone(context: Context): Boolean = SettingsFiles.deviceState(context).data.first()[DONE] ?: false

    /** Onboarding is done: the system dialog answered, or the app holds the permission. */
    suspend fun markDone(context: Context) {
        SettingsFiles.edit(SettingsFiles.deviceState(context)) { it[DONE] = true }
    }

    /** For tests: onboarding as before a first launch. */
    suspend fun forget(context: Context) {
        SettingsFiles.edit(SettingsFiles.deviceState(context)) { it.remove(DONE) }
    }
}

object OnboardingTags {
    const val SCREEN = "onboarding"
}

/** Whether onboarding is [showing], and its "Allow" button's action. */
@Stable
class OnboardingState internal constructor(showing: Boolean, private val ask: () -> Unit) {
    var showing by mutableStateOf(showing)
        private set

    /** Brings up the system dialog. */
    fun allow() = ask()

    /** The library takes over. */
    internal fun finish() {
        showing = false
    }
}

/**
 * Onboarding for this launch. Without the permission the done flag is read before the first frame, as the theme is, so
 * the library never flashes before it. The dialog's answer, whatever it is, finishes onboarding; so does a grant made
 * elsewhere while the app was away (the system settings), checked whenever the activity resumes.
 */
@Composable
fun rememberOnboarding(): OnboardingState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    lateinit var state: OnboardingState
    val finish = {
        state.finish()
        scope.launch { Onboarding.markDone(context) }
        Unit
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Access granted: the library starts watching the index and syncs, with its progress on the screen.
        if (granted) Library.start(context)
        finish()
    }
    state = remember {
        val granted = context.hasAudioPermission()
        val done = granted || runBlocking { Onboarding.isDone(context) }
        OnboardingState(Onboarding.shows(granted, done), ask = { launcher.launch(AudioPermission.name) })
    }
    LifecycleResumeEffect(Unit) {
        if (context.hasAudioPermission()) {
            if (state.showing) finish()
            // A phone that already gave access never sees onboarding, now or later.
            scope.launch { if (!Onboarding.isDone(context)) Onboarding.markDone(context) }
        }
        onPauseOrDispose { }
    }
    return state
}

private fun Context.hasAudioPermission() =
    ContextCompat.checkSelfPermission(this, AudioPermission.name) == PackageManager.PERMISSION_GRANTED

/** Onboarding while [state] shows it, otherwise [content] (the library). */
@Composable
fun OnboardingGate(state: OnboardingState, content: @Composable () -> Unit) {
    if (state.showing) OnboardingScreen(onAllow = state::allow) else content()
}

/** What PiPlayer is, why it needs access to the audio files, and "Allow" (docs/library.md §14.1). */
@Composable
fun OnboardingScreen(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag(OnboardingTags.SCREEN)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.onboarding_what), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.onboarding_why), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.onboarding_allow))
            }
            Text(
                text = stringResource(R.string.onboarding_later),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
