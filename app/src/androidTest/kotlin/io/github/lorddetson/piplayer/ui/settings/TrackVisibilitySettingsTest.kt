package io.github.lorddetson.piplayer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.diagnostics.TrackVisibilityFinding
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TrackVisibilitySettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theLibraryActionUsesTheDocumentPickerAndReportsAnInaccessibleFile() {
        val context = TestFiles.targetContext
        var action: String? = null
        val picker = picker { action = it.action }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                PiPlayerTheme { SettingsScreen(onBack = {}, onOpenFolders = {}) }
            }
        }

        compose.onNodeWithText(context.getString(R.string.track_visibility_check)).performScrollTo().performClick()

        assertThat(compose.runOnIdle { action }).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        compose.waitUntilExactlyOneExists(hasText(context.getString(R.string.track_visibility_inaccessible)), timeoutMillis = 5_000)
    }

    @Test
    fun cancellingASlowFileCheckLetsTheUserCheckAgain() {
        val context = TestFiles.targetContext
        val calls = AtomicInteger()
        val cancelled = AtomicBoolean()
        val picker = picker { }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                PiPlayerTheme {
                    TrackVisibilitySetting(HiddenFiles(), check = { _, _ ->
                        if (calls.incrementAndGet() == 1) {
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.set(true)
                            }
                        }
                        TrackVisibilityFinding.Visible
                    })
                }
            }
        }

        val check = compose.onNodeWithText(context.getString(R.string.track_visibility_check))
        check.performClick()
        compose.waitUntil(5_000) { calls.get() == 1 }

        compose.onNodeWithText(context.getString(R.string.settings_dialog_cancel)).performClick()

        compose.waitUntil(5_000) { cancelled.get() }
        check.performClick()
        compose.waitUntilExactlyOneExists(hasText(context.getString(R.string.track_visibility_visible)), timeoutMillis = 5_000)
    }

    private fun picker(onLaunch: (Intent) -> Unit): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                onLaunch(contract.createIntent(TestFiles.targetContext, input))
                dispatchResult(requestCode, Uri.parse("content://media/external_primary/audio/media/${Long.MAX_VALUE}"))
            }
        }
    }
}
