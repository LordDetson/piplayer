package io.github.lorddetson.piplayer.ui.settings

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.NowPlayingLayout
import io.github.lorddetson.piplayer.settings.NowPlayingElement
import io.github.lorddetson.piplayer.settings.NowPlayingPanelSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.ThemePackageCodec
import io.github.lorddetson.piplayer.settings.ThemePackageRead
import io.github.lorddetson.piplayer.settings.librarytabs.LibraryTabsSettings
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ThemePackageScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = TestFiles.targetContext
    private val settings = AppearanceSettings(context)
    private lateinit var packageFile: File

    @Before
    fun setInitialAppearance() {
        packageFile = File.createTempFile("theme-package-", ".json", context.cacheDir)
        runBlocking {
            settings.setTheme(ThemeMode.SYSTEM)
            settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        }
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    check(contract is ActivityResultContracts.OpenDocument || contract is ActivityResultContracts.CreateDocument)
                    dispatchResult(requestCode, Uri.fromFile(packageFile))
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                PiPlayerTheme { SettingsScreen(onBack = {}, onOpenFolders = {}) }
            }
        }
    }

    @After
    fun resetAppearance() {
        packageFile.delete()
        runBlocking {
            settings.setTheme(ThemeMode.SYSTEM)
            settings.setNowPlayingLayout(NowPlayingLayout.STANDARD)
        }
    }

    @Test
    fun reviewCancelApplyAndUndoAThemePackage() {
        packageFile.writeText("""{"version":1,"name":"Round trip","base":"graphite","layout":"expanded",
            "palette":{"backgroundRgb":12345}}""")
        importPackage()
        await("theme_package_preview")
        assertThat(settings.read().customTheme).isNull()
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.STANDARD)
        compose.onNodeWithTag("theme_package_cancel").performClick()
        assertThat(settings.read().customTheme).isNull()

        importPackage()
        await("theme_package_preview")
        compose.onNodeWithTag("theme_package_apply").performClick()
        compose.waitUntil(5_000) { settings.read().customTheme?.name == "Round trip" }
        assertThat(settings.read().customTheme?.palette?.backgroundRgb).isEqualTo(12345)
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.EXPANDED)
        val savedTheme = checkNotNull(settings.read().customTheme)
        val panel = LibraryTabsSettings(context)
        val playerPanel = NowPlayingPanelSettings(context)
        runBlocking {
            val inherited = panel.panel.first().layout
            panel.setButtonVisible("search", "search" in inherited.buttons.hidden, savedTheme.id)
            panel.setVisible("artists", false, savedTheme.id)
            playerPanel.edit(savedTheme.id) { current -> current.copy(hidden = current.hidden + NowPlayingElement.COVER) }
        }

        compose.onNodeWithTag("theme_package_export").performScrollTo().performClick()
        await("theme_package_status")
        val exported = packageFile.inputStream().use(ThemePackageCodec::decode) as ThemePackageRead.Loaded
        assertThat(exported.theme.panel).isEqualTo(runBlocking { panel.panel.first().layout })
        assertThat(exported.theme.player?.hidden).contains(NowPlayingElement.COVER)

        await("theme_package_undo")
        compose.onNodeWithTag("theme_package_undo").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.read().customTheme == null }
        assertThat(settings.read().nowPlayingLayout).isEqualTo(NowPlayingLayout.STANDARD)
        compose.onNodeWithTag("saved_theme_${savedTheme.id}").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.read().customTheme?.id == savedTheme.id }
        assertThat(runBlocking { panel.panel.first().layout }).isEqualTo(exported.theme.panel)
        assertThat(runBlocking { playerPanel.state.first().panel }).isEqualTo(exported.theme.player)

        runBlocking { settings.setTheme(ThemeMode.SYSTEM) }
        assertThat(runBlocking { panel.panel.first().layout }).isNotEqualTo(exported.theme.panel)
        importPackage()
        await("theme_package_preview")
        compose.onNodeWithTag("theme_package_apply").performClick()
        compose.waitUntil(5_000) { settings.read().customTheme?.id?.let { it != savedTheme.id } == true }
        assertThat(runBlocking { panel.panel.first().layout }).isEqualTo(exported.theme.panel)
        assertThat(runBlocking { playerPanel.state.first().panel }).isEqualTo(exported.theme.player)
    }

    @Test
    fun exportProducesAReadablePackageAndAnInvalidImportKeepsTheCurrentAppearance() {
        compose.onNodeWithTag("theme_package_export").performScrollTo().performClick()
        await("theme_package_status")
        val export = packageFile.inputStream().use(ThemePackageCodec::decode)
        assertThat(export).isInstanceOf(ThemePackageRead.Loaded::class.java)
        assertThat((export as ThemePackageRead.Loaded).theme.layout).isEqualTo(NowPlayingLayout.STANDARD)

        packageFile.writeText("""{"version":1,"name":"Broken","base":"graphite","code":"test"}""")
        importPackage()
        await("theme_package_error")
        assertThat(settings.read().theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.read().customTheme).isNull()
    }

    private fun importPackage() = compose.onNodeWithTag("theme_package_import").performScrollTo().performClick()

    private fun await(tag: String) = compose.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 5_000)
}
