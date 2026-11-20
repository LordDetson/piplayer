package io.github.lorddetson.piplayer.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface RadioInstallState {
    data object Idle : RadioInstallState
    data object Offer : RadioInstallState
    data class Downloading(val percent: Int? = null, val canCancel: Boolean = true) : RadioInstallState
    data object StoreConfirmation : RadioInstallState
    data object OpenRadio : RadioInstallState
    data object Failed : RadioInstallState
}

internal interface RadioInstaller {
    val installed: Boolean
    fun start(update: (RadioInstallState) -> Unit)
    fun cancel()
}

/** The entry point can show the offer without starting any Play install session. Runs on the main thread. */
internal class RadioInstallController(private val installer: RadioInstaller) {
    private val current = MutableStateFlow<RadioInstallState>(RadioInstallState.Idle)
    val state = current.asStateFlow()

    fun open() {
        if (current.value != RadioInstallState.Idle) return
        current.value = if (installer.installed) RadioInstallState.OpenRadio else RadioInstallState.Offer
    }

    fun dismiss() {
        if (current.value == RadioInstallState.Offer || current.value == RadioInstallState.Failed) {
            current.value = RadioInstallState.Idle
        }
    }

    fun install() {
        if (current.value != RadioInstallState.Offer && current.value != RadioInstallState.Failed) return
        if (installer.installed) {
            current.value = RadioInstallState.OpenRadio
        } else {
            current.value = RadioInstallState.Downloading()
            installer.start { current.value = it }
        }
    }

    fun cancel() {
        val currentState = current.value
        if (currentState is RadioInstallState.Downloading && currentState.canCancel) {
            current.value = currentState.copy(canCancel = false)
            installer.cancel()
        } else if (currentState == RadioInstallState.StoreConfirmation) {
            current.value = RadioInstallState.Downloading(canCancel = false)
            installer.cancel()
        }
    }

    fun consumeOpen(): Boolean = current.compareAndSet(RadioInstallState.OpenRadio, RadioInstallState.Idle)

    fun launchFailed() {
        current.value = RadioInstallState.Failed
    }
}
