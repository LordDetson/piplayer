package io.github.lorddetson.piplayer.ui.settings

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.songs.SongsTags
import io.github.lorddetson.piplayer.ui.theme.ThemeWindow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The theme a test starts with, saved as a choice would save it, and [kept] as the night mode the system keeps for the
 * app on Android 12 and later: another one than [mode] stands for a restore of the settings onto a new device. System
 * again afterwards.
 */
private class ThemeRule(private val mode: ThemeMode, private val kept: ThemeMode = mode) : ExternalResource() {
    override fun before() = set(mode, kept)
    override fun after() = set(ThemeMode.SYSTEM, ThemeMode.SYSTEM)

    private fun set(mode: ThemeMode, kept: ThemeMode) {
        runBlocking {
            val settings = AppearanceSettings(TestFiles.targetContext)
            settings.setTheme(mode)
            settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        }
        ThemeWindow.remember(TestFiles.targetContext, kept)
    }
}

/** The Settings screen and the theme choice (docs/library.md §3.6), on a light system. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    private val context = TestFiles.targetContext
    private fun text(id: Int) = context.getString(id)

    private val songs = SongSourceRule { listOf(SongSourceRule.song(1, "Alpha")) }
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(SystemNight(false)).around(ThemeRule(ThemeMode.SYSTEM))
        .around(songs).around(compose)

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

    /**
     * Chooses a theme and waits until it is saved, advancing frames. No wait for idle right after the click: the click
     * may create the activity again, and such a wait that starts on the old activity may never end.
     */
    private fun choose(label: Int, saved: ThemeMode) {
        compose.waitUntilExactlyOneExists(hasText(text(label)), timeoutMillis = 5_000)
        // The Library section above it may push the theme below the bottom of a small screen (docs/library.md §5.7).
        compose.onNodeWithText(text(label)).performScrollTo().performClick()
        runCatching { compose.waitUntil(5_000) { AppearanceSettings(context).readTheme() == saved } }
        assertThat(AppearanceSettings(context).readTheme()).isEqualTo(saved)
    }

    @Test
    fun theChosenThemeAppliesAtOnceToTheScreenItsWindowAndTheBarsAndIsKept() {
        openSettings()
        assertThat(brightness()).describedAs("system theme on a light system").isGreaterThan(0.6)
        ShownWindow.assertFollows(compose, dark = false)

        val light = ShownWindow.resumed()
        choose(R.string.theme_dark, ThemeMode.DARK)
        ShownWindow.assertFollows(compose, dark = true, replaced = light)
        assertThat(brightness()).describedAs("dark").isLessThan(0.3)

        // Back to the system's theme in the same session, and the other way.
        val dark = ShownWindow.resumed()
        choose(R.string.theme_system, ThemeMode.SYSTEM)
        ShownWindow.assertFollows(compose, dark = false, replaced = dark)
        assertThat(brightness()).describedAs("system again").isGreaterThan(0.6)

        // Light on a light system changes nothing on the screen, so the screen stays as it is.
        val shown = ShownWindow.resumed()
        choose(R.string.theme_light, ThemeMode.LIGHT)
        runCatching { compose.waitUntil(1_000) { ShownWindow.resumed() !== shown } }
        assertThat(ShownWindow.resumed()).describedAs("the activity shown").isSameAs(shown)
        ShownWindow.assertFollows(compose, dark = false)
        assertThat(brightness()).describedAs("light").isGreaterThan(0.6)
    }

    @Test
    fun builtInPalettesHavePreviewsAndCanBeSelected() {
        openSettings()
        val palettes = listOf(
            Triple(R.string.theme_amoled, ThemeMode.AMOLED, true),
            Triple(R.string.theme_graphite, ThemeMode.GRAPHITE, true),
            Triple(R.string.theme_warm_light, ThemeMode.WARM_LIGHT, false),
        )
        for ((label, mode, dark) in palettes) {
            scrollSettingsTo(label)
            compose.onNodeWithTag("theme-preview-${mode.stored}", useUnmergedTree = true).assertExists()
            choose(label, mode)
            ShownWindow.assertFollows(compose, dark)
            if (dark) assertThat(brightness()).isLessThan(0.3) else assertThat(brightness()).isGreaterThan(0.6)
        }
    }

    @Test
    fun readyThemesCanBeChosenWithTheirOwnPaletteAndPresentation() {
        openSettings()
        fun choosePreset(id: String, presentation: NowPlayingLayout) {
            val choice = "theme-$id"
            compose.onNodeWithTag(SettingsTags.CONTENT).performScrollToNode(hasTestTag(choice))
            compose.onNodeWithTag("theme-preview-$id", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(choice).performClick()
            val settings = AppearanceSettings(context)
            compose.waitUntil(5_000) { settings.readTheme().stored == id }
            assertThat(settings.read().nowPlayingLayout).isEqualTo(presentation)
        }
        choosePreset("cover_focus", NowPlayingLayout.MINIMAL)
        ShownWindow.assertFollows(compose, dark = true)
        assertThat(brightness()).isLessThan(0.3)
        choosePreset("control_deck", NowPlayingLayout.EXPANDED)
        ShownWindow.assertFollows(compose, dark = false)
        assertThat(brightness()).isGreaterThan(0.6)
    }

    @Test
    fun paletteAndPlayerLayoutAreSavedIndependently() {
        openSettings()
        choose(R.string.theme_warm_light, ThemeMode.WARM_LIGHT)
        scrollSettingsTo(R.string.settings_now_playing_layout)
        compose.onNodeWithText(text(R.string.settings_now_playing_layout)).performClick()
        compose.onNodeWithText(text(R.string.now_playing_layout_expanded)).performClick()

        val settings = AppearanceSettings(context)
        compose.waitUntil(5_000) { settings.read().nowPlayingLayout == NowPlayingLayout.EXPANDED }
        assertThat(settings.readTheme()).isEqualTo(ThemeMode.WARM_LIGHT)

        scrollSettingsTo(R.string.theme_graphite)
        choose(R.string.theme_graphite, ThemeMode.GRAPHITE)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.EXPANDED)
    }

    private fun scrollSettingsTo(label: Int) {
        compose.onNodeWithTag(SettingsTags.CONTENT).performScrollToNode(hasText(text(label)))
    }
}

/**
 * A theme saved before the app starts applies when it starts, against the system's (docs/library.md §3.6), even when
 * the night mode the system keeps for the app on Android 12 and later is the other one, as after a restore of the
 * settings onto a new device: the activity's own configuration carries the choice before Compose draws, so its window
 * background and its system bars follow it from the first activity shown on.
 */
abstract class SavedThemeTest(private val saved: ThemeMode, systemNight: Boolean) {

    private val songs = SongSourceRule { listOf(SongSourceRule.song(1, "Alpha")) }
    private val shown = ShownThemes()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val other = if (saved.isDark(false)) ThemeMode.LIGHT else ThemeMode.DARK

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission).around(SystemNight(systemNight))
        .around(ThemeRule(saved, kept = other)).around(songs).around(shown).around(compose)

    protected fun assertTheAppStartsInTheSavedTheme() {
        val dark = saved.isDark(false)
        ShownWindow.assertFollows(compose, dark)
        // Every activity shown, from the first on; the system may create it once more when it takes the choice.
        val expected = ShownThemes.Shown(night = dark, darkStatusIcons = !dark, darkNavigationIcons = !dark)
        assertThat(shown.shown).describedAs("activities shown").hasSizeBetween(1, 2).allMatch { it == expected }
        val pixels = compose.onNode(isRoot()).captureToImage().toPixelMap()
        var sum = 0.0
        var count = 0
        for (x in 0 until pixels.width step 8) for (y in 0 until pixels.height step 8) {
            val color = pixels[x, y]
            sum += 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
            count++
        }
        if (dark) assertThat(sum / count).isLessThan(0.3) else assertThat(sum / count).isGreaterThan(0.6)
    }
}

@RunWith(AndroidJUnit4::class)
class SavedDarkThemeTest : SavedThemeTest(ThemeMode.DARK, systemNight = false) {
    @Test
    fun aSavedDarkThemeAppliesWhenTheAppStartsOnALightSystem() = assertTheAppStartsInTheSavedTheme()
}

@RunWith(AndroidJUnit4::class)
class SavedLightThemeTest : SavedThemeTest(ThemeMode.LIGHT, systemNight = true) {
    @Test
    fun aSavedLightThemeAppliesWhenTheAppStartsOnADarkSystem() = assertTheAppStartsInTheSavedTheme()
}

@RunWith(AndroidJUnit4::class)
class SavedAmoledThemeTest : SavedThemeTest(ThemeMode.AMOLED, systemNight = false) {
    @Test
    fun aSavedAmoledThemeAppliesWhenTheAppStartsOnALightSystem() = assertTheAppStartsInTheSavedTheme()
}
