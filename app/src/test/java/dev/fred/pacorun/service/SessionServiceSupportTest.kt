package dev.fred.pacorun.service

import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionServiceSupportTest {
    @Test
    fun locationForegroundStartRequiresFineLocationPermission() {
        assertFalse(SessionStartPrerequisites(hasFineLocation = false).canStartLocationForegroundService)
        assertTrue(SessionStartPrerequisites(hasFineLocation = true).canStartLocationForegroundService)
    }

    @Test
    fun notificationChangesOnlyWhenItsDisplayedPhaseChanges() {
        val tracker = SessionNotificationTracker()
        val run = running(RoutinePhase.RUN)

        assertTrue(tracker.shouldUpdate(RunSessionState.AcquiringGps))
        assertFalse(tracker.shouldUpdate(RunSessionState.AcquiringGps))
        assertTrue(tracker.shouldUpdate(run))
        assertFalse(tracker.shouldUpdate(run.copy(elapsedMillis = 1_000L, pace = PaceResult(PaceState.TOO_FAST, 9f, null))))
        assertTrue(tracker.shouldUpdate(run.copy(phase = RoutinePhase.WALK)))
    }

    private fun running(phase: RoutinePhase) = RunSessionState.Running(
        phase = phase,
        pace = PaceResult(PaceState.GOOD, 8f, null),
        speedKmh = 8f,
        gpsReliable = true,
        startedAtElapsedMillis = 0L,
        elapsedMillis = 0L,
        intervalRemainingMillis = 60_000L,
        totalDistanceMeters = 0.0,
        routineDistanceMeters = 0.0,
    )
}
