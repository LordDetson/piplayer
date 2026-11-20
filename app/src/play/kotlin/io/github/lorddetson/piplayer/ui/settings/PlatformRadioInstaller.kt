package io.github.lorddetson.piplayer.ui.settings

import android.app.Application
import android.content.IntentSender.SendIntentException
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

/** Converts Play's callbacks into state for the entry point. The view model owns the active listener. */
internal class PlatformRadioInstaller(application: Application) : RadioInstaller {
    private val manager = SplitInstallManagerFactory.create(application)
    private var active: Install? = null

    val entryAvailable = true
    override val installed: Boolean get() = MODULE in manager.installedModules

    override fun start(update: (RadioInstallState) -> Unit) {
        val install = Install(update)
        active = install
        manager.registerListener(install.listener)
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(MODULE).build())
            .addOnSuccessListener { sessionId ->
                if (active !== install) return@addOnSuccessListener
                if (sessionId == 0) {
                    install.finish(RadioInstallState.OpenRadio)
                } else {
                    install.bind(sessionId)
                }
            }
            .addOnFailureListener { install.finish(RadioInstallState.Failed) }
    }

    override fun cancel() {
        active?.let { install ->
            install.cancelRequested = true
            install.sendCancel()
        }
    }

    fun showStoreConfirmation(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val install = active ?: return
        val confirmation = install.confirmation ?: return
        install.confirmation = null
        val shown = try {
            manager.startConfirmationDialogForResult(confirmation, launcher)
        } catch (_: SendIntentException) {
            false
        }
        if (!shown) {
            install.finish(RadioInstallState.Failed)
        }
    }

    fun close() {
        active?.let { manager.unregisterListener(it.listener) }
        active = null
    }

    private inner class Install(private val update: (RadioInstallState) -> Unit) {
        var cancelRequested = false
        var confirmation: SplitInstallSessionState? = null
        private var sessionId: Int? = null
        private val earlyStates = mutableMapOf<Int, SplitInstallSessionState>()
        private var cancelSent = false
        private var confirmationShown = false
        private var sawSessionState = false

        val listener = SplitInstallStateUpdatedListener(::onState)

        fun bind(id: Int) {
            sessionId = id
            if (cancelRequested) sendCancel()
            val early = earlyStates[id]
            earlyStates.clear()
            if (early != null) {
                onState(early)
            } else {
                // A session may already be in progress when Play returns its ID. Keep a live update if it wins the race.
                manager.getSessionState(id).addOnSuccessListener { state ->
                    if (!sawSessionState) onState(state)
                }
            }
        }

        private fun onState(state: SplitInstallSessionState) {
            if (active !== this || MODULE !in state.moduleNames()) return
            val id = sessionId
            if (id == null) {
                earlyStates[state.sessionId()] = state
                return
            }
            if (id != state.sessionId()) return
            sawSessionState = true
            when (state.status()) {
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                    if (!cancelRequested && !confirmationShown) {
                        confirmationShown = true
                        confirmation = state
                        update(RadioInstallState.StoreConfirmation)
                    }
                }
                SplitInstallSessionStatus.INSTALLED -> finish(RadioInstallState.OpenRadio)
                SplitInstallSessionStatus.CANCELED -> finish(RadioInstallState.Idle)
                SplitInstallSessionStatus.FAILED -> finish(RadioInstallState.Failed)
                else -> {
                    val percentage = state.totalBytesToDownload().takeIf { it > 0L }?.let { total ->
                        (state.bytesDownloaded().coerceIn(0L, total).toDouble() / total * 100).toInt()
                    }
                    val canCancel = !cancelRequested && state.status() in CANCELLABLE_STATES
                    update(RadioInstallState.Downloading(percentage, canCancel))
                }
            }
        }

        fun sendCancel() {
            val id = sessionId ?: return
            if (cancelSent) return
            cancelSent = true
            manager.cancelInstall(id).addOnFailureListener {
                // The request may have already entered installation. Follow that session to its terminal state.
                manager.getSessionState(id).addOnSuccessListener(::onState)
                    .addOnFailureListener { finish(RadioInstallState.Failed) }
            }
        }

        fun finish(state: RadioInstallState) {
            if (active !== this) return
            close()
            update(if (cancelRequested) RadioInstallState.Idle else state)
        }
    }

    private companion object {
        const val MODULE = "radio"
        val CANCELLABLE_STATES = setOf(
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
        )
    }
}
