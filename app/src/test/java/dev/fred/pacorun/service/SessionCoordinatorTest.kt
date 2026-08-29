package dev.fred.pacorun.service

import dev.fred.pacorun.audio.AudioCue
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutineCue
import dev.fred.pacorun.routine.RoutineInput
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.routine.RoutineResult
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    @Test
    fun profileResetPrecedesPaceEvaluationAndRoutineCuesPrecedePaceCues() {
        val events = mutableListOf<String>()
        val routine = FakeRoutineEngine(events, profileChanged = true)
        val pace = FakePaceEvaluator(events, PaceResult(PaceState.TOO_FAST, 9f, Cue.TOO_FAST))
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { cues, _ -> events += "cues:${cues.joinToString()}" },
            publish = published::add,
        )

        coordinator.tick(
            elapsedRealtimeMillis = 0L,
            sample = reliableSample(9f, 0L),
            position = LocationPosition(0.0, 0.0, 0L),
        )
        events.clear()
        coordinator.tick(
            elapsedRealtimeMillis = 1_000L,
            sample = reliableSample(9f, 1_000L),
            position = LocationPosition(0.0, .00001, 1_000L),
        )

        assertEquals(listOf("routine", "reset", "pace", "cues:ROUTINE_START, TOO_FAST"), events)
        assertEquals(1.11, routine.lastInput.reliableDistanceDeltaMeters, .02)
        val state = published.last() as RunSessionState.Running
        assertEquals(RoutinePhase.RUN, state.phase)
        assertEquals(9f, state.speedKmh)
        assertEquals(4_000L, state.intervalRemainingMillis)
        assertEquals(3.0, state.totalDistanceMeters, .001)
        assertEquals(2.0, state.routineDistanceMeters, .001)
    }

    @Test
    fun weakGpsStillAdvancesRoutineButDoesNotEvaluatePaceOrDistance() {
        val events = mutableListOf<String>()
        val routine = FakeRoutineEngine(events, profileChanged = false)
        val pace = FakePaceEvaluator(events, PaceResult(PaceState.GOOD, 8f, Cue.GOOD_PACE))
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { cues, _ -> events += "cues:${cues.joinToString()}" },
            publish = published::add,
        )

        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .00001, 1_000L))

        assertEquals(listOf("routine", "cues:ROUTINE_START"), events)
        assertFalse(routine.lastInput.gpsReliable)
        assertEquals(0.0, routine.lastInput.reliableDistanceDeltaMeters, .001)
        val state = published.single() as RunSessionState.Running
        assertFalse(state.gpsReliable)
        assertEquals(null, state.speedKmh)
    }

    private fun reliableSample(speedKmh: Float, time: Long) = LocationSample(speedKmh, .2f, true, time)
    private fun unreliableSample(time: Long) = LocationSample(0f, null, false, time)

    private class FakeRoutineEngine(
        private val events: MutableList<String>,
        private val profileChanged: Boolean,
    ) : SessionRoutineEngine {
        lateinit var lastInput: RoutineInput

        override fun accept(input: RoutineInput): RoutineResult {
            events += "routine"
            lastInput = input
            return RoutineResult(
                phase = RoutinePhase.RUN,
                activeRange = SpeedRange(7f, 8f),
                intervalRemainingMillis = 4_000L,
                totalDistanceMeters = 3.0,
                routineDistanceMeters = 2.0,
                cues = listOf(RoutineCue.ROUTINE_START),
                profileChanged = profileChanged,
            )
        }
    }

    private class FakePaceEvaluator(
        private val events: MutableList<String>,
        private val result: PaceResult,
    ) : SessionPaceEvaluator {
        override fun updateConfig(config: PaceConfig) {
            events += "reset"
        }

        override fun accept(sample: LocationSample): PaceResult {
            events += "pace"
            return result
        }
    }
}
