package dev.fred.pacorun.audio

import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

private class FakeAudioDucking : AudioDucking {
    val requestCount = AtomicInteger(0)
    val releaseCount = AtomicInteger(0)
    var focusLostCallback: (() -> Unit)? = null
    var grantFocus = true

    override fun requestDucking(onFocusLost: () -> Unit): Boolean {
        requestCount.incrementAndGet()
        focusLostCallback = onFocusLost
        return grantFocus
    }

    override fun releaseDucking() {
        releaseCount.incrementAndGet()
    }
}

@RunWith(AndroidJUnit4::class)
class CuePlayerAudioDuckingTest {
    @Test
    fun enqueueingACueRequestsDucking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ducking = FakeAudioDucking()
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>(), ducking)
        }

        thread { player.enqueue(listOf(AudioCue.ROUTINE_START), .5f) }.join()
        instrumentation.waitForIdleSync()

        assertEquals(1, ducking.requestCount.get())
    }

    @Test
    fun closingThePlayerReleasesDucking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ducking = FakeAudioDucking()
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>(), ducking)
        }

        thread {
            player.enqueue(listOf(AudioCue.ROUTINE_START), .5f)
            player.close()
        }.join()
        instrumentation.waitForIdleSync()

        assertEquals(1, ducking.releaseCount.get())
    }

    @Test
    fun losingAudioFocusStopsAndDropsTheQueueInsteadOfResuming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ducking = FakeAudioDucking()
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>(), ducking)
        }

        thread { player.enqueue(listOf(AudioCue.ROUTINE_START, AudioCue.ROUTINE_END), .5f) }.join()
        instrumentation.waitForIdleSync()
        assertEquals(1, player.queueSizeForTest()) // ROUTINE_END still pending behind the playing cue

        instrumentation.runOnMainSync { ducking.focusLostCallback?.invoke() }
        instrumentation.waitForIdleSync()

        // A pause-and-resume design would keep the focus request alive across the interruption;
        // stop-and-drop releases it immediately because playback is considered fully stopped.
        assertEquals(1, ducking.releaseCount.get())
        // Proves player.stop() actually ran, not just the playing/queue bookkeeping.
        var playbackState = -1
        instrumentation.runOnMainSync { playbackState = player.playbackStateForTest() }
        assertEquals(Player.STATE_IDLE, playbackState)
        // Proves queue.clear() actually ran -- ROUTINE_END must not survive to play later.
        assertEquals(0, player.queueSizeForTest())

        thread { player.enqueue(listOf(AudioCue.ROUTINE_START), .5f) }.join()
        instrumentation.waitForIdleSync()

        assertEquals(2, ducking.requestCount.get())
    }

    @Test
    fun focusLossIsHandledAsAPostedMessageNotReentrantly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ducking = FakeAudioDucking()
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>(), ducking)
        }

        thread { player.enqueue(listOf(AudioCue.ROUTINE_START), .5f) }.join()
        instrumentation.waitForIdleSync()

        var stateRightAfterInvokingCallback = -1
        instrumentation.runOnMainSync {
            ducking.focusLostCallback?.invoke()
            // If stopAndDropOnFocusLoss() ever regresses to running synchronously, nested
            // inside this same call (i.e. reentrantly inside AudioManager's own focus-change
            // callback), the player would already be STATE_IDLE right here. Posting defers the
            // actual stop to a later message on this looper, so it must not be IDLE yet.
            stateRightAfterInvokingCallback = player.playbackStateForTest()
        }
        assertNotEquals(Player.STATE_IDLE, stateRightAfterInvokingCallback)

        instrumentation.waitForIdleSync()
        var stateAfterIdle = -1
        instrumentation.runOnMainSync { stateAfterIdle = player.playbackStateForTest() }
        assertEquals(Player.STATE_IDLE, stateAfterIdle)
    }

    @Test
    fun deniedAudioFocusDropsTheCueInsteadOfPlayingUnprotected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ducking = FakeAudioDucking().apply { grantFocus = false }
        lateinit var player: CuePlayer
        instrumentation.runOnMainSync {
            player = CuePlayer(ApplicationProvider.getApplicationContext<Context>(), ducking)
        }

        thread { player.enqueue(listOf(AudioCue.ROUTINE_START), .5f) }.join()
        instrumentation.waitForIdleSync()

        assertEquals(1, ducking.requestCount.get())
        assertEquals(0, player.queueSizeForTest())
        var playbackState = -1
        instrumentation.runOnMainSync { playbackState = player.playbackStateForTest() }
        assertEquals(Player.STATE_IDLE, playbackState)
    }
}
