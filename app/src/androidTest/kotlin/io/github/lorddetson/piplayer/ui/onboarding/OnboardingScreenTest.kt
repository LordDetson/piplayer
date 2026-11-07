package io.github.lorddetson.piplayer.ui.onboarding

import android.content.pm.PackageManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.library.songs.AudioPermission
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.library.LibraryTags
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

private val context = TestFiles.targetContext

private fun text(id: Int) = context.getString(id)

/** Onboarding is not done yet when the activity starts. */
private object OnboardingNotDone : ExternalResource() {
    override fun before() = runBlocking { Onboarding.forget(context) }
}

/** The onboarding screen and the gate in front of the library (docs/library.md §14.1). */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theScreenSaysWhatPiPlayerIsAndWhyItNeedsAccess() {
        var asked = false
        compose.setContent { OnboardingScreen(onAllow = { asked = true }) }

        compose.onNodeWithText(text(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_what)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_why)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_later)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_allow)).performClick()

        assertThat(asked).isTrue()
    }

    @Test
    fun theGateShowsTheLibraryOnceTheDialogHasAnswered() {
        lateinit var state: OnboardingState
        state = OnboardingState(showing = true, ask = { state.finish() })
        compose.setContent { OnboardingGate(state) { Text("The library") } }

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText("The library").assertDoesNotExist()

        compose.onNodeWithText(text(R.string.onboarding_allow)).performClick()

        compose.onNodeWithText("The library").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun onboardingKeepsTheMiniPlayerOfAFileOpenedFromAnotherApp() {
        compose.setContent {
            OnboardingGate(OnboardingState(showing = true, ask = {}), bottomBar = { Text("Mini player") }) { Text("The library") }
        }

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText("Mini player").assertIsDisplayed()
    }

    @Test
    fun aGateNotShowingGoesStraightToTheLibrary() {
        compose.setContent { OnboardingGate(OnboardingState(showing = false, ask = {})) { Text("The library") } }

        compose.onNodeWithText("The library").assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun doneIsKeptInTheDeviceState() {
        runBlocking {
            Onboarding.forget(context)
            assertThat(Onboarding.isDone(context)).isFalse()

            Onboarding.markDone(context)

            assertThat(Onboarding.isDone(context)).isTrue()
        }
    }
}

/** A launch with the permission: no onboarding, which is done from then on (docs/library.md §14.1). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingWithThePermissionTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(OnboardingNotDone).around(compose)

    @Test
    fun theLibraryShowsAtOnceAndOnboardingCountsAsDone() {
        compose.waitUntilExactlyOneExists(hasTestTag(LibraryTags.TABS), timeoutMillis = 10_000)
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()

        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { Onboarding.isDone(context) } }
    }
}

/**
 * A first launch without the permission. A grant cannot be undone inside a test run, and the test helper and CI grant it
 * before every run, so this runs only when started by hand after the permission was revoked (docs/library.md §14.4):
 * `adb shell pm revoke io.github.lorddetson.piplayer android.permission.READ_EXTERNAL_STORAGE` (READ_MEDIA_AUDIO on API
 * 33 and later), then `adb shell am instrument -w -e class io.github.lorddetson.piplayer.ui.onboarding.OnboardingFirstLaunchTest
 * io.github.lorddetson.piplayer.test/io.github.lorddetson.piplayer.PiPlayerTestRunner`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingFirstLaunchTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(OnboardingNotDone).around(compose)

    @Test
    fun aFirstLaunchWithoutThePermissionShowsOnboardingInsteadOfTheLibrary() {
        assumeFalse(
            "The permission was granted earlier in this run",
            context.checkSelfPermission(AudioPermission.name) == PackageManager.PERMISSION_GRANTED,
        )

        compose.waitUntilExactlyOneExists(hasTestTag(OnboardingTags.SCREEN), timeoutMillis = 10_000)
        compose.onNodeWithTag(LibraryTags.TABS).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_allow)).assertIsDisplayed()
        assertThat(runBlocking { Onboarding.isDone(context) }).isFalse()
    }
}
