package io.github.lorddetson.piplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.ThemeMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.lorddetson.piplayer.playback.OpenWith
import io.github.lorddetson.piplayer.ui.PiPlayerApp
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appearance = AppearanceSettings(this)
        // Read before the first frame, so that the app never starts in the other theme (docs/library.md §3.6).
        val initialTheme = appearance.readTheme()
        setContent {
            val theme by appearance.theme.collectAsStateWithLifecycle(initialTheme)
            val dark = theme.isDark(isSystemInDarkTheme())
            // The system bars' icons follow the app's theme, not only the system's.
            LaunchedEffect(theme, dark) {
                if (theme == ThemeMode.SYSTEM) {
                    enableEdgeToEdge()
                } else {
                    val style = if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
            }
            PiPlayerTheme(darkTheme = dark) {
                // Test tags double as resource IDs, so that the checks of the release build find elements by them
                // (docs/library.md §2.2).
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    PiPlayerApp(playerViewModel)
                }
            }
        }
        if (savedInstanceState == null) openFrom(intent)
    }

    /** The activity is singleTop: a second file from a file manager comes here, not to a new activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        val uris = intent?.let(OpenWith::uris).orEmpty()
        if (uris.isNotEmpty()) playerViewModel.open(uris)
    }
}
