package io.github.lorddetson.piplayer.ui.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RadioInstallControllerTest {
    private val installer = TestInstaller()
    private val controller = RadioInstallController(installer)

    @Test
    fun openingAndDismissingThePromptDoesNotRequestInstallation() {
        controller.open()
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Offer)
        assertThat(installer.requests).isEqualTo(0)

        controller.dismiss()
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Idle)
        assertThat(installer.requests).isEqualTo(0)
    }

    @Test
    fun installationNeedsAnExplicitChoiceAndStartsOnce() {
        controller.install()
        assertThat(installer.requests).isEqualTo(0)

        controller.open()
        controller.install()
        controller.install()
        assertThat(installer.requests).isEqualTo(1)
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Downloading())

        installer.update(RadioInstallState.Downloading(percent = 45))
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Downloading(percent = 45))
        installer.update(RadioInstallState.OpenRadio)
        assertThat(controller.consumeOpen()).isTrue()
        assertThat(controller.consumeOpen()).isFalse()
    }

    @Test
    fun anInstalledModuleOpensImmediately() {
        installer.installed = true

        controller.open()

        assertThat(controller.consumeOpen()).isTrue()
        assertThat(installer.requests).isEqualTo(0)
    }

    @Test
    fun cancelDismissesAfterTheInstallerReportsCancellation() {
        controller.open()
        controller.install()
        controller.cancel()

        assertThat(installer.cancellations).isEqualTo(1)
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Downloading(canCancel = false))
        installer.update(RadioInstallState.Idle)
        assertThat(controller.state.value).isEqualTo(RadioInstallState.Idle)
    }

    @Test
    fun aFailedDownloadOnlyRetriesOnAnExplicitChoice() {
        controller.open()
        controller.install()
        assertThat(installer.requests).isEqualTo(1)
        installer.update(RadioInstallState.Failed)

        assertThat(controller.state.value).isEqualTo(RadioInstallState.Failed)
        assertThat(installer.requests).isEqualTo(1)
        controller.install()
        assertThat(installer.requests).isEqualTo(2)
    }

    private class TestInstaller : RadioInstaller {
        override var installed = false
        var requests = 0
        var cancellations = 0
        lateinit var update: (RadioInstallState) -> Unit

        override fun start(update: (RadioInstallState) -> Unit) {
            requests++
            this.update = update
        }

        override fun cancel() {
            cancellations++
        }
    }
}
