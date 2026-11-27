package io.github.lorddetson.piplayer.settings.configuration

import android.app.LocaleManager
import android.content.res.Resources
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.MainActivity
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.playback.PlaybackExtras
import io.github.lorddetson.piplayer.playback.PlaybackSessionCallback
import io.github.lorddetson.piplayer.playback.PlaybackTesting.connectController
import io.github.lorddetson.piplayer.playback.PlaybackTesting.eventually
import io.github.lorddetson.piplayer.playback.PlaybackTesting.onMain
import io.github.lorddetson.piplayer.playback.PlaybackTesting.stopAndRelease
import io.github.lorddetson.piplayer.playback.PlaybackTesting.timerArgs
import io.github.lorddetson.piplayer.settings.AppLanguage
import io.github.lorddetson.piplayer.settings.AppLanguageChange
import io.github.lorddetson.piplayer.settings.AppLanguageSettings
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.player.TimerChoice
import io.github.lorddetson.piplayer.ui.player.TimerChoiceStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationIntegrationTest {
    private val context = TestFiles.targetContext
    private val store = ConfigurationStore(context)

    @Test
    fun importAndUndoUseTheAppLocaleAndKeepTheSystemLocale() = runBlocking<Unit> {
        val backend = AppLanguageSettings.transactionLocale(context)
        val old = backend.read()
        val system = systemLanguageTags()
        val target = if (backend.selected(old) == AppLanguage.RUSSIAN) AppLanguage.ENGLISH else AppLanguage.RUSSIAN
        val expected = if (Build.VERSION.SDK_INT >= 33) AppLanguageChange.APPLIED else AppLanguageChange.RECREATE
        try {
            val result = store.apply(preview(ConfigurationPatch(language = target))) as ConfigurationWriteResult.Applied
            assertThat(result.languageChange).isEqualTo(expected)
            assertThat(backend.read()).isEqualTo(target.tag)
            assertThat(systemLanguageTags()).isEqualTo(system)
            assertThat(store.undo(result.undo)).isEqualTo(ConfigurationWriteResult.Restored(expected))
            assertThat(backend.read()).isEqualTo(old)
            assertThat(systemLanguageTags()).isEqualTo(system)
        } finally {
            if (backend.read() != old) backend.write(old)
        }
    }

    @Test
    fun importAndUndoRefreshTheRetainedTimerChoiceWithoutTouchingTheRunningTimer() = runBlocking<Unit> {
        TimerChoiceStore.clearForTests(context)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: PlayerViewModel
            activity.onActivity { model = ViewModelProvider(it)[PlayerViewModel::class.java] }
            val controller = connectController()
            try {
                eventually("the player model restores its queue") { model.restored.value }
                val started = onMain {
                    controller.sendCustomCommand(PlaybackSessionCallback.SLEEP_TIMER_COMMAND, timerArgs(60_000))
                }.get(5, TimeUnit.SECONDS)
                assertThat(started.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
                fun extras() = PlaybackExtras.from(onMain { controller.sessionExtras })
                eventually("the service starts the sleep timer") { extras().sleepDeadlineMs != null }
                val original = extras().sleepDeadlineMs
                val patch = ConfigurationPatch(sleepTimer = SleepTimerPatch(hours = 2, minutes = 17, finishTrack = true))
                val result = store.apply(preview(patch)) as ConfigurationWriteResult.Applied
                eventually("the existing player model receives the imported defaults") {
                    model.timerChoice.value == TimerChoice(2, 17, true)
                }
                assertThat(extras().sleepDeadlineMs).isEqualTo(original)
                assertThat(extras().finishTrack).isFalse()
                assertThat(store.undo(result.undo)).isInstanceOf(ConfigurationWriteResult.Restored::class.java)
                eventually("the existing player model receives the restored defaults") {
                    model.timerChoice.value == TimerChoice.DEFAULT
                }
                assertThat(extras().sleepDeadlineMs).isEqualTo(original)
                assertThat(extras().finishTrack).isFalse()
            } finally {
                controller.stopAndRelease()
                TimerChoiceStore.clearForTests(context)
            }
        }
    }

    private suspend fun preview(patch: ConfigurationPatch): ConfigurationPreview {
        val result = store.preview(ConfigurationCodec.encode(patch).inputStream())
        return (result as ConfigurationPreviewResult.Ready).preview
    }

    private fun systemLanguageTags(): String = if (Build.VERSION.SDK_INT >= 33) {
        // The in-process Resources configuration can include the app locale.
        checkNotNull(context.getSystemService(LocaleManager::class.java)).systemLocales.toLanguageTags()
    } else {
        Resources.getSystem().configuration.locales.toLanguageTags()
    }
}
