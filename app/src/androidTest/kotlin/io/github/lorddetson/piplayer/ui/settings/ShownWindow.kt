package io.github.lorddetson.piplayer.ui.settings

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.lorddetson.piplayer.MainActivity
import org.assertj.core.api.Assertions.assertThat
import org.junit.rules.ExternalResource

/** The resumed activity's window and system bars follow a theme (docs/library.md §3.6). */
object ShownWindow {

    /** The activity shown now, if any. */
    fun resumed(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }

    private fun Activity.isNight() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * Waits until the activity shown is in the [dark] or light theme, then checks its system bars. When the choice
     * creates the activity again, [replaced] is the activity shown before it, and only a new one counts. [compose]
     * advances its frames meanwhile, so that the app's effects run; it never waits for idle, which may never come while
     * the activity is created again.
     */
    fun assertFollows(compose: ComposeTestRule, dark: Boolean, replaced: Activity? = null, timeoutMs: Long = 5_000) {
        runCatching { compose.waitUntil(timeoutMs) { resumed().let { it != null && it !== replaced && it.isNight() == dark } } }
        val activity = resumed()
        if (replaced != null) assertThat(activity).describedAs("the activity shown, created again").isNotSameAs(replaced)
        assertThat(activity?.isNight()).describedAs("night mode of the activity shown").isEqualTo(dark)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bars = WindowCompat.getInsetsController(activity!!.window, activity.window.decorView)
            assertThat(bars.isAppearanceLightStatusBars).describedAs("dark icons on the status bar").isEqualTo(!dark)
            assertThat(bars.isAppearanceLightNavigationBars).describedAs("dark icons on the navigation bar").isEqualTo(!dark)
        }
    }
}

/** Each MainActivity's theme when it is shown: its night mode and whether its system bars have dark icons. */
class ShownThemes : ExternalResource() {

    data class Shown(val night: Boolean, val darkStatusIcons: Boolean, val darkNavigationIcons: Boolean)

    val shown: List<Shown> get() = synchronized(list) { list.toList() }

    private val list = mutableListOf<Shown>()

    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (stage != Stage.RESUMED || activity !is MainActivity) return@ActivityLifecycleCallback
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        synchronized(list) { list += Shown(night, bars.isAppearanceLightStatusBars, bars.isAppearanceLightNavigationBars) }
    }

    override fun before() = ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)

    override fun after() = ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
}
