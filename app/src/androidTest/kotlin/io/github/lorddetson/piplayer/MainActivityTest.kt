package io.github.lorddetson.piplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The library screen with access granted, as on every launch after the first (docs/library.md §14.1). */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(composeRule)

    @Test
    fun showsTheAppNameFromResources() {
        val appName = composeRule.activity.getString(R.string.app_name)

        composeRule.onNodeWithText(appName).assertIsDisplayed()
    }

    @Test
    fun debugBuildsOpenTheIdentificationSpikeFromTheMainScreen() {
        assumeTrue(BuildConfig.DEBUG)
        val activity = composeRule.activity

        composeRule.onNodeWithText(activity.getString(R.string.spike_open)).performClick()

        composeRule.onNodeWithText(activity.getString(R.string.spike_title)).assertIsDisplayed()
        composeRule.onNodeWithText(activity.getString(R.string.spike_choose_file)).assertIsDisplayed()
    }

}
