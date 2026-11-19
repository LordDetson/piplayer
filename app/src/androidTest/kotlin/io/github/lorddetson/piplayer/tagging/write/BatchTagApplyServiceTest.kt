package io.github.lorddetson.piplayer.tagging.write

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lorddetson.piplayer.TestFiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchTagApplyServiceTest {
    @Test
    fun anApprovedBatchCanRunInAPrivateDataSyncService() {
        val context = TestFiles.targetContext
        val launch = Intent().setClassName(context, "${context.packageName}.tagging.write.BatchTagApplyService")
        @Suppress("DEPRECATION")
        val service = context.packageManager.queryIntentServices(launch, 0).singleOrNull()?.serviceInfo

        assertThat(service).describedAs("private service for an approved batch tag write").isNotNull()
        checkNotNull(service)
        assertThat(service.exported).isFalse()
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (Build.VERSION.SDK_INT >= 34) {
            assertThat(context.packageManager.checkPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC, context.packageName))
                .isEqualTo(PackageManager.PERMISSION_GRANTED)
        }
    }
}
