package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/** Radio can be opened if this build already includes the feature. Play installs use a separate installer. */
internal class PlatformRadioInstaller(private val application: Application) : RadioInstaller {
    val entryAvailable: Boolean get() = installed

    override val installed: Boolean
        get() = try {
            application.classLoader.loadClass(RADIO_ACTIVITY)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    override fun start(update: (RadioInstallState) -> Unit) = update(RadioInstallState.Failed)

    override fun cancel() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun showStoreConfirmation(launcher: ActivityResultLauncher<IntentSenderRequest>) = Unit

    fun close() = Unit
}
