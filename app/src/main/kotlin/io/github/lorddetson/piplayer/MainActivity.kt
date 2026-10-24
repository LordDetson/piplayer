package io.github.lorddetson.piplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        setContent {
            PiPlayerTheme {
                PiPlayerApp(playerViewModel)
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
