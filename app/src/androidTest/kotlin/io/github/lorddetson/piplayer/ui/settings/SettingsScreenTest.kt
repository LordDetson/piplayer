package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Settings screen and the theme choice (docs/library.md §3.6). The emulator's system theme is light. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    /** The theme the app starts with; System again afterwards. */
    private class ThemeRule(private val mode: ThemeMode) : ExternalResource() {
        override fun before() = runBlocking { AppearanceSettings(TestFiles.targetContext).setTheme(mode) }
        override fun after() = runBlocking { AppearanceSettings(TestFiles.targetContext).setTheme(ThemeMode.SYSTEM) }
    }

    private val songs = SongSourceRule { listOf(SongSourceRule.song(1, "Alpha")) }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(ThemeRule(ThemeMode.SYSTEM)).around(songs).around(compose)

    /** The mean brightness of the whole screen, from 0 (black) to 1 (white). */
    private fun brightness(): Double {
        compose.waitForIdle()
        val pixels = compose.onNode(isRoot()).captureToImage().toPixelMap()
        var sum = 0.0
        var count = 0
        for (x in 0 until pixels.width step 8) for (y in 0 until pixels.height step 8) {
            val color = pixels[x, y]
            sum += 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
            count++
        }
        return sum / count
    }

    private fun openSettings() {
        compose.waitUntilExactlyOneExists(hasContentDescription(text(R.string.settings_open)), timeoutMillis = 10_000)
        compose.onNode(hasContentDescription(text(R.string.settings_open))).performClick()
        compose.waitUntilExactlyOneExists(hasText(text(R.string.settings_appearance)), timeoutMillis = 5_000)
    }

    @Test
    fun settingsOpenFromTheSongsScreenAndBackReturns() {
        openSettings()
        compose.onNodeWithText(text(R.string.settings_title)).assertExists()

        Espresso.pressBack()

        compose.waitUntilExactlyOneExists(androidx.compose.ui.test.hasTestTag(SongsTags.LIST), timeoutMillis = 5_000)
    }

    @Test
    fun theChosenThemeAppliesAtOnceAndIsKept() {
        openSettings()
        assertThat(brightness()).describedAs("system theme on a light system").isGreaterThan(0.6)

        compose.onNodeWithText(text(R.string.theme_dark)).performClick()
        compose.waitForIdle()
        assertThat(brightness()).describedAs("dark").isLessThan(0.3)
        assertThat(AppearanceSettings(context).readTheme()).isEqualTo(ThemeMode.DARK)

        compose.onNodeWithText(text(R.string.theme_light)).performClick()
        compose.waitForIdle()
        assertThat(brightness()).describedAs("light").isGreaterThan(0.6)
        assertThat(AppearanceSettings(context).readTheme()).isEqualTo(ThemeMode.LIGHT)
    }
}

/**
 * A dark theme saved before the app starts applies when it starts (docs/library.md §3.6). That the very first frame is
 * already dark comes from reading the choice before `setContent`; a capture cannot tell that frame from the next one.
 */
@RunWith(AndroidJUnit4::class)
class SavedThemeTest {

    private class ThemeRule(private val mode: ThemeMode) : ExternalResource() {
        override fun before() = runBlocking { AppearanceSettings(TestFiles.targetContext).setTheme(mode) }
        override fun after() = runBlocking { AppearanceSettings(TestFiles.targetContext).setTheme(ThemeMode.SYSTEM) }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(ThemeRule(ThemeMode.DARK)).around(compose)

    @Test
    fun aSavedDarkThemeAppliesWhenTheAppStarts() {
        val pixels = compose.onNode(isRoot()).captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        val brightness = 0.2126 * center.red + 0.7152 * center.green + 0.0722 * center.blue

        assertThat(brightness).isLessThan(0.3)
    }
}
