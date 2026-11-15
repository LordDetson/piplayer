package io.github.lorddetson.piplayer.identification

import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.PiPlayerApplication
import io.github.lorddetson.piplayer.TestFiles
import io.github.lorddetson.piplayer.fakes.TestServices
import io.github.lorddetson.piplayer.identification.caa.FailureTtlPolicy
import io.github.lorddetson.piplayer.identification.caa.RoomFailureCache
import io.github.lorddetson.piplayer.identification.musicbrainz.ReleaseDto
import io.github.lorddetson.piplayer.identification.release.RoomReleaseCache
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.net.OnlineConsent
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.SettingsFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // The gate is the persisted opt-in of the settings file (TI §9), not one of its own.
        assertThat(services.gate).isSameAs(app.onlineGate)
        assertThat(services.baseUrls).isEqualTo(ProviderBaseUrls.PRODUCTION)

        // A new install: the settings file holds no consent, whatever an earlier manual run on this device left there.
        val file = SettingsFiles.settings(app)
        val kept = runBlocking { file.data.first() }
        try {
            runBlocking {
                file.edit {
                    it.remove(IdentificationSettings.ENABLED)
                    it.remove(IdentificationSettings.VERSION)
                }
                withTimeout(5_000) { app.onlineGate.consent.first { it == OnlineConsent.NONE } }
            }
            assertThat(services.gate.isOpen()).isFalse()
        } finally {
            runBlocking {
                file.edit { preferences ->
                    kept[IdentificationSettings.ENABLED]?.let { preferences[IdentificationSettings.ENABLED] = it }
                    kept[IdentificationSettings.VERSION]?.let { preferences[IdentificationSettings.VERSION] = it }
                }
            }
        }
    }

    @Test
    fun theAppsServicesKeepTheirCachesInTheLibraryDatabase() = runBlocking<Unit> {
        val services = IdentificationServices.of(app)
        assertThat(services.releaseCache).isInstanceOf(RoomReleaseCache::class.java)
        assertThat(services.failureCache).isInstanceOf(RoomFailureCache::class.java)

        val release = ReleaseDto(id = "0e1d7c2b-5a4f-4c3e-9b8a-7d6c5b4a3f21", title = "Cache check")
        val address = "https://coverartarchive.org/release/${release.id}/front-500"
        val dao = Library.database(app).identificationCache()
        try {
            services.releaseCache.put(release)
            services.failureCache.put(address, FailureTtlPolicy(java.time.Clock.systemUTC()).notFound())
            assertThat(dao.release(release.id)).isNotNull()
            assertThat(dao.api(address)!!.status).isEqualTo(404)
            assertThat(services.releaseCache.get(release.id)).isEqualTo(release)
        } finally {
            dao.dropRelease(release.id)
            services.failureCache.remove(address)
        }
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
