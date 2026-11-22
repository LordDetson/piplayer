package io.github.lorddetson.piplayer.radio

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.RadioSplitCompat
import io.github.lorddetson.piplayer.net.OnlineGate
import io.github.lorddetson.piplayer.playback.RadioPlayback
import io.github.lorddetson.piplayer.settings.AppLanguageSettings
import io.github.lorddetson.piplayer.settings.Appearance
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import io.github.lorddetson.piplayer.ui.theme.ThemeWindow

class RadioActivity : ComponentActivity() {
    private lateinit var initialAppearance: Appearance

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        initialAppearance = AppearanceSettings(newBase).read()
        AppLanguageSettings.configurationOverride(newBase, ThemeWindow.override(initialAppearance.theme))
            ?.let(::applyOverrideConfiguration)
        RadioSplitCompat.attachActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeWindow.remember(this, initialAppearance.theme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appearanceSettings = AppearanceSettings(this)
        val saved = SavedRadioStations(this)
        // Directory requests start only when the user submits a search or loads another page.
        val directory = RadioBrowserClient(OnlineGate { true })

        setContent {
            val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle(initialAppearance)
            LaunchedEffect(appearance.theme) {
                ThemeWindow.follow(this@RadioActivity, appearance.theme, initialAppearance.theme)
            }
            PiPlayerTheme(appearance.theme, appearance.customTheme?.palette) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    RadioScreen(
                        directory = directory,
                        saved = saved,
                        playStation = { name, url -> RadioPlayback.play(this@RadioActivity, name, url) },
                        onBack = ::finish,
                    )
                }
            }
        }
    }
}
