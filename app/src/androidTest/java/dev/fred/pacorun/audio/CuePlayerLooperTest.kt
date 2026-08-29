package dev.fred.pacorun.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CuePlayerLooperTest {
    @Test
    fun publicOperationsFromWorkerThreadDoNotAccessMedia3Directly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>())
        }
        val failure = AtomicReference<Throwable>()

        val worker = thread {
            try {
                player.enqueue(listOf(AudioCue.ROUTINE_START, AudioCue.SPEED_UP), .5f)
                player.close()
                player.close()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.join()
        instrumentation.waitForIdleSync()

        assertTrue(!worker.isAlive)
        assertNull(failure.get())
    }
}
