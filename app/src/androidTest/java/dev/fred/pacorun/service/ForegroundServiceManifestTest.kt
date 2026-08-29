package dev.fred.pacorun.service

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceManifestTest {
    @Test
    fun declaresLocationAndMediaPlaybackForegroundServiceCapabilities() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, RunSessionService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in packageInfo.requestedPermissions.orEmpty())
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            serviceInfo.foregroundServiceType,
        )
    }
}
