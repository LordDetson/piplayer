package io.github.lorddetson.piplayer.ui.settings

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.R
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.SongSourceRule
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.AppLanguageSettings
import io.github.lorddetson.piplayer.settings.CustomTheme
import io.github.lorddetson.piplayer.settings.CustomThemesSettings
import io.github.lorddetson.piplayer.settings.HiddenFiles
import io.github.lorddetson.piplayer.settings.LibrarySettings
import io.github.lorddetson.piplayer.settings.SettingsFiles
import io.github.lorddetson.piplayer.settings.ThemeMode
import io.github.lorddetson.piplayer.settings.UiCanvasBounds
import io.github.lorddetson.piplayer.settings.UiCanvasItem
import io.github.lorddetson.piplayer.settings.UiCanvasLayout
import io.github.lorddetson.piplayer.settings.UiCanvasSettings
import io.github.lorddetson.piplayer.settings.UiControlMotion
import io.github.lorddetson.piplayer.settings.UiControlStyle
import io.github.lorddetson.piplayer.settings.UiIconAsset
import io.github.lorddetson.piplayer.settings.UiIconFormat
import io.github.lorddetson.piplayer.settings.UiSurface
import io.github.lorddetson.piplayer.settings.UiThemeCanvases
import io.github.lorddetson.piplayer.settings.configuration.AppearancePatch
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationCodec
import io.github.lorddetson.piplayer.settings.configuration.ConfigurationPatch
import io.github.lorddetson.piplayer.settings.configuration.Explicit
import io.github.lorddetson.piplayer.settings.configuration.ThemeLayoutPatch
import io.github.lorddetson.piplayer.ui.GrantAudioPermission
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ConfigurationSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = TestFiles.targetContext
    private val library = LibrarySettings(context)
    private val settings = SettingsFiles.settings(context)
    private lateinit var document: File
    private var originalShowSystemSounds: Boolean? = null
    private var failPicker = false
    private var pickerResult: Uri? = null
    private val createDocumentLaunches = AtomicInteger()
    private var onCreateDocument: (() -> Unit)? = null
    private var delayPickerResult = false
    private val deferredPickerResult = AtomicReference<((Uri?) -> Unit)?>(null)
    private val settingsOwner = mutableStateOf<ViewModelStoreOwner>(newSettingsOwner())

    @Before
    fun setUp() {
        document = File.createTempFile("piplayer-configuration-", ".json", context.cacheDir)
        pickerResult = Uri.fromFile(document)
        runBlocking {
            originalShowSystemSounds = settings.data.first()[HiddenFiles.SHOW_SYSTEM_SOUNDS]
            library.setShowSystemSounds(false)
        }
        val picker = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    when (contract) {
                        is ActivityResultContracts.OpenDocument -> assertThat("application/json" in (input as Array<*>)).isTrue()
                        is ActivityResultContracts.CreateDocument -> {
                            createDocumentLaunches.incrementAndGet()
                            assertThat(input).isEqualTo("PiPlayer-configuration.json")
                            onCreateDocument?.invoke()
                        }
                        else -> error("Unexpected contract: ${contract::class.java.name}")
                    }
                    if (failPicker) throw ActivityNotFoundException()
                    if (delayPickerResult) {
                        deferredPickerResult.set { uri ->
                            val code = if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK
                            dispatchResult(requestCode, code, uri?.let { Intent().setData(it) })
                        }
                    } else {
                        dispatchResult(requestCode, pickerResult)
                    }
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                val model = viewModel(viewModelStoreOwner = settingsOwner.value) {
                    SettingsViewModel(context.applicationContext as Application)
                }
                PiPlayerTheme { SettingsScreen(onBack = {}, onOpenFolders = {}, viewModel = model) }
            }
        }
    }

    @After
    fun restoreSettings() {
        compose.runOnIdle { settingsOwner.value.viewModelStore.clear() }
        document.delete()
        runBlocking {
            SettingsFiles.edit(settings) { preferences ->
                val original = originalShowSystemSounds
                if (original == null) preferences.remove(HiddenFiles.SHOW_SYSTEM_SOUNDS)
                else preferences[HiddenFiles.SHOW_SYSTEM_SOUNDS] = original
            }
            library.readHiddenFiles()
        }
    }

    @Test
    fun exportReviewCancelApplyAndUndoConfiguration() {
        document.writeText("x".repeat(64_000))
        click("configuration_export")
        await("configuration_status")
        val exported = Json.parseToJsonElement(document.readText()).jsonObject
        assertThat(exported.getValue("version")).isEqualTo(JsonPrimitive(1))
        val exportedLibrary = exported.getValue("library").jsonObject
        assertThat(exportedLibrary.getValue("showSystemSounds").jsonPrimitive.boolean).isFalse()
        val changedLibrary = JsonObject(exportedLibrary + ("showSystemSounds" to JsonPrimitive(true)))
        document.writeText(JsonObject(exported + ("library" to changedLibrary)).toString())

        click("configuration_import")
        await("configuration_preview")
        await("configuration_change_library_showSystemSounds")
        assertThat(showSystemSounds()).isFalse()
        compose.onNodeWithTag("configuration_cancel").performClick()
        compose.onNodeWithTag("configuration_preview").assertDoesNotExist()
        assertThat(showSystemSounds()).isFalse()

        click("configuration_import")
        await("configuration_preview")
        compose.onNodeWithTag("configuration_apply").performClick()
        await("configuration_undo")
        assertThat(showSystemSounds()).isTrue()

        click("configuration_undo")
        await("configuration_status")
        assertThat(showSystemSounds()).isFalse()
        compose.onNodeWithTag("configuration_undo").assertDoesNotExist()
    }

    @Test
    fun identicalAndInvalidFilesDoNotOfferApply() {
        click("configuration_export")
        await("configuration_status")
        click("configuration_import")
        await("configuration_preview")
        await("configuration_no_changes")
        compose.onNodeWithTag("configuration_apply").assertDoesNotExist()
        compose.onNodeWithTag("configuration_cancel").performClick()

        document.writeText("{")
        click("configuration_import")
        await("configuration_error")
        compose.onNodeWithTag("configuration_preview").assertDoesNotExist()
        compose.onNodeWithTag("configuration_undo").assertDoesNotExist()
        assertThat(showSystemSounds()).isFalse()
    }

    @Test
    fun stalePreviewAndUnavailableDocumentsAreReportedWithoutWrites() {
        document.writeText("""{"version":1,"library":{"showSystemSounds":true}}""")
        click("configuration_import")
        await("configuration_preview")
        runBlocking { library.setShowSystemSounds(true) }
        compose.onNodeWithTag("configuration_apply").performClick()
        await("configuration_error")
        compose.onNodeWithTag("configuration_undo").assertDoesNotExist()
        assertThat(showSystemSounds()).isTrue()

        pickerResult = Uri.fromFile(File(document.parentFile, "${document.name}.missing"))
        click("configuration_import")
        await("configuration_error")
        compose.onNodeWithTag("configuration_preview").assertDoesNotExist()

        pickerResult = Uri.fromFile(document.parentFile!!)
        click("configuration_export")
        await("configuration_error")
        assertThat(document.readText()).contains("showSystemSounds")
    }

    @Test
    fun canceledAndUnavailablePickersLeaveSettingsUnchanged() {
        pickerResult = null
        click("configuration_import")
        click("configuration_export")
        compose.waitUntil(5_000) { createDocumentLaunches.get() == 1 }
        compose.onNodeWithTag("configuration_preview").assertDoesNotExist()
        compose.onNodeWithTag("configuration_status").assertDoesNotExist()
        assertThat(document.length()).isZero()

        failPicker = true
        click("configuration_import")
        await("configuration_error")
        compose.onNodeWithTag("configuration_preview").assertDoesNotExist()
        assertThat(showSystemSounds()).isFalse()
    }

    @Test
    fun previewShowsDeepEqualSizeChangesAndExpandsTheirDetails() {
        val theme = ThemeMode.DARK
        val key = UiCanvasSettings.key(theme.stored)
        val original = runBlocking { settings.data.first()[key] }
        val prefix = "A".repeat(250)
        val cover = canvasItem("now:cover", prefix + "OLD!", UiControlMotion.PRESS)
        val updated = canvasItem("now:cover", prefix + "NEW!", UiControlMotion.BOUNCE)
        try {
            runBlocking {
                SettingsFiles.edit(settings) {
                    UiCanvasSettings.store(it, theme.stored, UiThemeCanvases(
                        mapOf(UiSurface.NOW_PLAYING to UiCanvasLayout(listOf(cover))),
                    ))
                }
            }
            document.writeBytes(ConfigurationCodec.encode(ConfigurationPatch(
                appearance = AppearancePatch(builtInOverrides = mapOf(theme to ThemeLayoutPatch(
                    canvases = mapOf(UiSurface.NOW_PLAYING to Explicit(UiCanvasLayout(listOf(updated)))),
                ))),
            )))
            click("configuration_import")
            await("configuration_preview")
            compose.onNode(hasText("items[1].style.icon.payload")).assertExists()
            compose.onNode(hasText("OLD!", substring = true)).assertExists()
            compose.onNode(hasText("NEW!", substring = true)).assertExists()
            compose.onNode(hasText("items[1].style.motion")).assertDoesNotExist()
            compose.onNodeWithTag("configuration_change_details").performClick()
            compose.onNode(hasText("items[1].style.motion")).performScrollTo().assertExists()
            compose.onNodeWithTag("configuration_cancel").performClick()
        } finally {
            runBlocking {
                SettingsFiles.edit(settings) {
                    if (original == null) it.remove(key) else it[key] = original
                }
            }
        }
    }

    @Test
    fun oversizedExportDoesNotLaunchTheDestinationPicker() {
        val original = runBlocking { settings.data.first()[CustomThemesSettings.THEMES] }
        val ids = List(48) { UUID.randomUUID().toString() }
        val payload = "x".repeat(13_000)
        val layout = UiCanvasLayout(listOf(
            "now:cover", "now:more", "now:queue", "now:seek", "now:format", "now:next", "now:previous", "now:shuffle",
        ).map { canvasItem(it, payload) })
        try {
            runBlocking {
                SettingsFiles.edit(settings) { preferences ->
                    ids.forEachIndexed { index, id ->
                        val theme = CustomTheme("Export fixture $index", ThemeMode.DARK, id = id)
                        CustomThemesSettings.storeTheme(preferences, theme, select = false)
                        UiCanvasSettings.store(preferences, id, UiThemeCanvases(mapOf(UiSurface.NOW_PLAYING to layout)))
                    }
                }
            }
            click("configuration_export")
            await("configuration_error")
            assertThat(createDocumentLaunches.get()).isZero()
            assertThat(document.length()).isZero()
            compose.onNode(
                hasText("This configuration is too large to export.") or hasText("Конфигурация слишком большая для экспорта."),
            ).assertExists()
        } finally {
            runBlocking {
                SettingsFiles.edit(settings) {
                    ids.forEach { id -> it.remove(UiCanvasSettings.key(id)) }
                    if (original == null) it.remove(CustomThemesSettings.THEMES) else it[CustomThemesSettings.THEMES] = original
                }
            }
        }
    }

    @Test
    fun documentUsesThePreparedSnapshot() {
        onCreateDocument = { runBlocking { library.setShowSystemSounds(true) } }
        click("configuration_export")
        await("configuration_status")
        assertThat(showSystemSounds()).isTrue()
        val exported = Json.parseToJsonElement(document.readText()).jsonObject
        assertThat(exported.getValue("library").jsonObject.getValue("showSystemSounds").jsonPrimitive.boolean).isFalse()
    }

    @Test
    fun restoredImportResultOpensPreviewWithAFreshSettingsViewModel() {
        document.writeText("""{"version":1,"library":{"showSystemSounds":true}}""")
        delayPickerResult = true
        click("configuration_import")
        restoreSettingsViewModelAndDeliverResult(Uri.fromFile(document))
        await("configuration_preview")
        assertThat(showSystemSounds()).isFalse()
    }

    @Test
    fun restoredExportWithoutPreparedBytesReportsInterruptionAndLeavesTheDocumentUntouched() {
        document.writeText("Existing contents")
        delayPickerResult = true
        click("configuration_export")
        restoreSettingsViewModelAndDeliverResult(Uri.fromFile(document))
        await("configuration_error")
        compose.onNode(
            hasText("Export was interrupted. Start the export again.") or hasText("Экспорт прерван. Запустите экспорт ещё раз."),
        ).assertExists()
        assertThat(document.readText()).isEqualTo("Existing contents")
    }

    @Test
    fun previewDistinguishesDifferentActiveThemesWithTheSameName() {
        val previous = runBlocking { settings.data.first() }
        val first = CustomTheme("Shared name")
        val second = CustomTheme("Shared name")
        try {
            runBlocking {
                SettingsFiles.edit(settings) {
                    CustomThemesSettings.storeTheme(it, first, select = true)
                    CustomThemesSettings.storeTheme(it, second, select = false)
                }
            }
            document.writeText("""{"version":1,"appearance":{"activeTheme":"${second.id}"}}""")
            click("configuration_import")
            await("configuration_change_appearance_activeTheme")
            val label = hasText(first.name, substring = true)
            compose.waitUntilExactlyOneExists(label and hasText(first.id, substring = true), timeoutMillis = 5_000)
            compose.onNode(label and hasText(second.id, substring = true)).assertExists()
        } finally {
            runBlocking {
                SettingsFiles.edit(settings) { restored ->
                    for (key in listOf(CustomThemesSettings.THEMES, CustomThemesSettings.SELECTED)) {
                        val value = previous[key]
                        if (value == null) restored.remove(key) else restored[key] = value
                    }
                }
            }
        }
    }

    private fun restoreSettingsViewModelAndDeliverResult(uri: Uri?) {
        compose.waitUntil(5_000) { deferredPickerResult.get() != null }
        val dispatch = checkNotNull(deferredPickerResult.getAndSet(null))
        compose.runOnIdle {
            settingsOwner.value.viewModelStore.clear()
            settingsOwner.value = newSettingsOwner()
        }
        compose.runOnIdle { dispatch(uri) }
    }

    private fun newSettingsOwner(): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private fun canvasItem(id: String, payload: String, motion: UiControlMotion = UiControlMotion.NONE) = UiCanvasItem(
        id,
        UiCanvasBounds(0, 0, 100, 100),
        style = UiControlStyle(icon = UiIconAsset(UiIconFormat.PNG, payload), motion = motion),
    )

    private fun showSystemSounds() = library.readHiddenFiles().showSystemSounds

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun await(tag: String) = compose.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 5_000)
}

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ConfigurationSettingsRecreationTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(GrantAudioPermission)
        .around(SongSourceRule { listOf(SongSourceRule.song(1, "Alpha")) })
        .around(compose)

    @Test
    fun undoSurvivesTheLanguageChangeDuringImport() {
        val application = TestFiles.targetContext.applicationContext as PiPlayerApplication
        val original = AppLanguageSettings.read(application)
        val originalTag = compose.activity.resources.configuration.locales[0].language
        val chosen = if (originalTag == "ru") AppLanguage.ENGLISH else AppLanguage.RUSSIAN
        val changedTitle = if (chosen == AppLanguage.RUSSIAN) "Настройки" else "Settings"
        val document = File.createTempFile("piplayer-language-", ".json", application.cacheDir)
        document.writeText("""{"version":1,"language":"${chosen.tag}"}""")
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(document)))
                } else {
                    null
                }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            val settings = hasContentDescription(compose.activity.getString(R.string.settings_open))
            compose.waitUntilExactlyOneExists(settings, timeoutMillis = 10_000)
            compose.onNode(settings).performClick()
            compose.onNodeWithTag("configuration_import").performScrollTo().performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("configuration_preview"), timeoutMillis = 5_000)
            compose.onNodeWithTag("configuration_apply").performClick()
            compose.waitUntil(10_000) {
                ShownWindow.resumed()?.resources?.configuration?.locales?.get(0)?.language == chosen.tag
            }
            compose.waitUntilExactlyOneExists(hasTestTag(SettingsTags.TITLE) and hasText(changedTitle), timeoutMillis = 5_000)
            compose.waitUntilExactlyOneExists(hasTestTag("configuration_undo"), timeoutMillis = 5_000)
            compose.onNodeWithTag("configuration_undo").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                AppLanguageSettings.read(application) == original &&
                    ShownWindow.resumed()?.resources?.configuration?.locales?.get(0)?.language == originalTag
            }
            compose.onNodeWithTag("configuration_undo").assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
            document.delete()
            runBlocking { AppLanguageSettings.set(application, original) }
            application.applyLegacyLanguage()
        }
    }
}
