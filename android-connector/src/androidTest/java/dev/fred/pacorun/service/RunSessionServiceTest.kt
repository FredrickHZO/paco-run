package dev.fred.pacorun.service

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunSessionServiceTest {
    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    // Caso 10: il rischio più grave del documento -- il ciclo di vita del campo `sequencer`.
    // Senza l'azzeramento esplicito in stopSession(), una seconda sessione non partirebbe MAI:
    // il guard onStartCommand `if (!starting && sequencer == null) startSession()` resterebbe
    // permanentemente falso dopo la prima sessione.
    @Test
    fun secondSessionStartsAfterFirstIsStopped() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val startIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START)
        serviceRule.startService(startIntent)
        Thread.sleep(500) // lascia che startSession()/beginTracking() (coroutine) completino

        val stopIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_STOP)
        serviceRule.startService(stopIntent)
        Thread.sleep(500)

        // Se `sequencer` non fosse stato azzerato in stopSession(), questa seconda ACTION_START
        // non chiamerebbe mai startSession() di nuovo -- non c'è modo diretto di leggere il
        // campo privato `sequencer` da qui, quindi la prova osservabile è che RunSessionStore
        // torni in AcquiringGps/Running dopo questo secondo avvio, non resti Idle.
        val secondStartIntent = Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START)
        serviceRule.startService(secondStartIntent)
        Thread.sleep(500)

        assertNotNull(
            "a second ACTION_START after stopSession() must re-enter startSession(), " +
                "observable as RunSessionStore leaving Idle",
            RunSessionStore.state.value.takeIf { it != RunSessionState.Idle },
        )
    }
}
