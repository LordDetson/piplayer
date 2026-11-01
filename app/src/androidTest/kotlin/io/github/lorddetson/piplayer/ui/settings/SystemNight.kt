package io.github.lorddetson.piplayer.ui.settings

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Sets the system's night mode for a test and restores it afterwards, so that theme checks do not depend on the device. */
class SystemNight(private val night: Boolean) : ExternalResource() {

    private var before: String? = null
    private var wasNight = false

    override fun before() {
        before = shell("cmd uimode night").substringAfter("Night mode:", "").trim().ifEmpty { null }
        wasNight = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        shell("cmd uimode night " + if (night) "yes" else "no")
    }

    override fun after() {
        val mode = before ?: return
        // A schedule (auto or custom) applies its night mode only when the screen next turns off: the night mode it had
        // comes back first, so that later tests and the device do not keep the test's.
        if (mode != "yes" && mode != "no") shell("cmd uimode night " + if (wasNight) "yes" else "no")
        shell("cmd uimode night $mode")
    }

    private fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
    }
}
