package io.github.lorddetson.piplayer

import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only the launcher activity may be reachable from other apps. Library components that are exported must be
 * protected by a permission that ordinary apps cannot hold.
 */
@RunWith(AndroidJUnit4::class)
class ExportedComponentsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onlyIntendedComponentsAreExported() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val components: List<ComponentInfo> =
            info.activities.orEmpty().toList() + info.services.orEmpty() + info.receivers.orEmpty() +
                info.providers.orEmpty()

        val exported = components.filter { it.exported }.map { it.name to permissionOf(it) }
        val allowed = if (BuildConfig.DEBUG) ALLOWED + DEBUG_ONLY else ALLOWED

        assertThat(exported).isSubsetOf(allowed)
        assertThat(exported.map { it.first }).contains(MainActivity::class.java.name)
    }

    private fun permissionOf(component: ComponentInfo): String? = when (component) {
        is android.content.pm.ActivityInfo -> component.permission
        is android.content.pm.ServiceInfo -> component.permission
        is android.content.pm.ProviderInfo -> component.readPermission
        else -> null
    }

    private companion object {
        val ALLOWED = listOf(
            MainActivity::class.java.name to null,
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )

        /** Added only to debug builds by debugImplementation tooling: Compose previews and Compose UI tests. */
        val DEBUG_ONLY = listOf(
            "androidx.compose.ui.tooling.PreviewActivity" to null,
            "androidx.activity.ComponentActivity" to null,
        )
    }
}
