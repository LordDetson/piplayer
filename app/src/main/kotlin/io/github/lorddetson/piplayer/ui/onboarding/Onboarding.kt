package io.github.lorddetson.piplayer.ui.onboarding

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** The first launch's single screen (docs/library.md §14.1). */
object Onboarding {

    /** Whether the app shows onboarding: without the permission ([granted]) and before it is [done]. */
    fun shows(granted: Boolean, done: Boolean): Boolean = TODO("step 14")

    suspend fun isDone(context: Context): Boolean = TODO("step 14")

    suspend fun markDone(context: Context): Unit = TODO("step 14")

    suspend fun forget(context: Context): Unit = TODO("step 14")
}

object OnboardingTags {
    const val SCREEN = "onboarding"
}

@Stable
class OnboardingState internal constructor(showing: Boolean, private val ask: () -> Unit) {
    var showing by mutableStateOf(showing)
        private set

    fun allow() = ask()

    internal fun finish() {
        showing = false
    }
}

@Composable
fun OnboardingGate(state: OnboardingState, content: @Composable () -> Unit) {
    content()
}

@Composable
fun OnboardingScreen(onAllow: () -> Unit, modifier: Modifier = Modifier) {
}
