package io.github.lorddetson.piplayer.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import io.github.lorddetson.piplayer.settings.ThemeMode

/**
 * Brings the theme choice to the windows themselves, before Compose draws (docs/library.md §3.6): the window background
 * and the system bars follow the night mode of the activity's configuration.
 */
object ThemeWindow {

    /**
     * Android 12 and later keep a night mode for the app, which also themes the splash screen; setting it changes the
     * configuration, so the system creates the activity again when it differs.
     */
    fun remember(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val night = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(night)
    }

    /**
     * The activity's own configuration carries the choice too; null when the system decides. On Android 11 it is the
     * only way, and on later versions it covers a launch whose kept night mode differs from the choice, as after a
     * restore of the settings onto a new device.
     */
    fun override(mode: ThemeMode): Configuration? {
        if (mode == ThemeMode.SYSTEM) return null
        return Configuration().apply {
            uiMode = if (mode == ThemeMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }

    /**
     * The choice [mode] while [activity], created with [created], is shown. Android 12 and later get every choice, and
     * create the activity again whenever the night mode they keep changes what it shows; Android 11 creates it again here
     * when the choice turns the screen dark or light. A choice that keeps it as it is waits for the next creation, which
     * a change of the system's night mode brings anyway.
     */
    fun follow(activity: Activity, mode: ThemeMode, created: ThemeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remember(activity, mode)
            return
        }
        // The application's configuration is the system's; only the activity's own carries the override.
        val systemDark = activity.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (mode.isDark(systemDark) != created.isDark(systemDark)) activity.recreate()
    }
}
