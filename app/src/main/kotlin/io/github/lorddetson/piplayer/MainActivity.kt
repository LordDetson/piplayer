package io.github.lorddetson.piplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.lorddetson.piplayer.settings.Appearance
import io.github.lorddetson.piplayer.settings.AppearanceSettings
import io.github.lorddetson.piplayer.settings.AppLanguageSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.lorddetson.piplayer.playback.OpenWith
import io.github.lorddetson.piplayer.ui.PiPlayerApp
import io.github.lorddetson.piplayer.ui.Places
import io.github.lorddetson.piplayer.ui.SongsRoute
import io.github.lorddetson.piplayer.ui.player.PlayerViewModel
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme
import io.github.lorddetson.piplayer.ui.theme.ThemeWindow

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    private lateinit var initialAppearance: Appearance

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Read before the first frame, so that the app never starts in the other theme (docs/library.md §3.6).
        initialAppearance = AppearanceSettings(newBase).read()
        AppLanguageSettings.configurationOverride(newBase, ThemeWindow.override(initialAppearance.theme))
            ?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeWindow.remember(this, initialAppearance.theme)
        // The configuration's night mode follows the choice, so the bars' automatic style does too.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appearanceSettings = AppearanceSettings(this)
        setContent {
            val backStack = rememberNavBackStack(SongsRoute)
            val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle(initialAppearance)
            LaunchedEffect(appearance.theme) {
                ThemeWindow.follow(this@MainActivity, appearance.theme, initialAppearance.theme)
            }
            PiPlayerTheme(mode = appearance.theme, palette = appearance.customTheme?.palette) {
                // Test tags double as resource IDs, so that the checks of the release build find elements by them
                // (docs/library.md §2.2).
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    PiPlayerApp(playerViewModel, nowPlayingLayout = appearance.nowPlayingLayout, backStack = backStack)
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
        // A batch's notification opens the Fix tab (TI §8): the app takes the place asked for and forgets it.
        Places.of(intent)?.let(Places::request)
    }
}
