package io.github.lorddetson.piplayer.identification

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.TestServices
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's one set of identification services (M3 steps 1 and 11): behind the persisted opt-in, closed on a new install,
 * and replaceable by a test.
 */
@RunWith(AndroidJUnit4::class)
class IdentificationServicesAppTest {

    private val app = TestFiles.targetContext.applicationContext as PiPlayerApplication

    @Test
    fun theAppsServicesAreOneInstanceWithAClosedGate() {
        val services = IdentificationServices.of(app)
        assertThat(IdentificationServices.of(app)).isSameAs(services)
        assertThat(services.gate.isOpen()).isFalse()
        // The gate is the persisted opt-in of the settings file (TI §9), not one of its own.
        assertThat(services.gate).isSameAs(app.onlineGate)
        assertThat(services.baseUrls).isEqualTo(ProviderBaseUrls.PRODUCTION)
    }

    @Test
    fun aTestCanInstallItsOwnServices() {
        val unused = "https://localhost/".toHttpUrl()
        val own = TestServices.services(ProviderBaseUrls(unused, unused, unused))
        PiPlayerApplication.installedIdentificationServices = own
        try {
            assertThat(IdentificationServices.of(app)).isSameAs(own)
        } finally {
            PiPlayerApplication.installedIdentificationServices = null
        }
        assertThat(IdentificationServices.of(app)).isNotSameAs(own)
    }
}
