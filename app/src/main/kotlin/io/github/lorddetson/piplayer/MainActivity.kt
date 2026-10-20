package io.github.lorddetson.piplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.lorddetson.piplayer.ui.PiPlayerApp
import io.github.lorddetson.piplayer.ui.theme.PiPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PiPlayerTheme {
                PiPlayerApp()
            }
        }
    }
}

