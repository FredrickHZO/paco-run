package dev.fred.pacorun.routine

import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineEngineTest {
    @Test
    fun constantModeAlwaysStaysConstantWithoutRoutineCues() {
        val engine = RoutineEngine(RunPreferences())

        val result = engine.accept(input(time = 0L, distance = 100.0, speed = 6f))

        assertEquals(RoutinePhase.CONSTANT, result.phase)
        assertEquals(SpeedRange(7.5f, 8.5f), result.activeRange)
        assertNull(result.intervalRemainingMillis)
        assertEquals(100.0, result.totalDistanceMeters, .001)
        assertEquals(0.0, result.routineDistanceMeters, .001)
        assertEquals(emptyList<RoutineCue>(), result.cues)
        assertFalse(result.profileChanged)
    }

    @Test
    fun zeroWarmupStartsRunImmediatelyWithRoutineStart() {
        val result = RoutineEngine(preferences()).accept(input(time = 0L))

        assertEquals(RoutinePhase.RUN, result.phase)
        assertEquals(SpeedRange(9f, 10f), result.activeRange)
        assertEquals(20_000L, result.intervalRemainingMillis)
        assertEquals(listOf(RoutineCue.ROUTINE_START), result.cues)
        assertTrue(result.profileChanged)
    }

    @Test
    fun positiveWarmupStartsAtDistanceBoundary() {
        val engine = RoutineEngine(preferences(warmupKm = .001f))

        assertEquals(RoutinePhase.CONSTANT_BEFORE, engine.accept(input(time = 0L, distance = .5)).phase)
        val result = engine.accept(input(time = 1_000L, distance = .5))

        assertEquals(RoutinePhase.RUN, result.phase)
        assertEquals(listOf(RoutineCue.ROUTINE_START), result.cues)
        assertEquals(1.0, result.totalDistanceMeters, .001)
        assertEquals(0.0, result.routineDistanceMeters, .001)
    }

    @Test
    fun runAndWalkAlternateAtIndependentDurations() {
        val engine = RoutineEngine(preferences(runSeconds = 5, walkSeconds = 7))

        engine.accept(input(time = 0L))
        val walk = engine.accept(input(time = 5_000L))
        val run = engine.accept(input(time = 12_000L))

        assertEquals(RoutinePhase.WALK, walk.phase)
        assertEquals(SpeedRange(4f, 5f), walk.activeRange)
        assertEquals(7_000L, walk.intervalRemainingMillis)
        assertEquals(listOf(RoutineCue.WALK_ROUTINE), walk.cues)
        assertEquals(RoutinePhase.RUN, run.phase)
        assertEquals(5_000L, run.intervalRemainingMillis)
        assertEquals(listOf(RoutineCue.RUN_ROUTINE), run.cues)
    }

    @Test
    fun tenSecondWarningAndFinalThreeBeepsFireOnce() {
        val engine = RoutineEngine(preferences(runSeconds = 15))

        engine.accept(input(time = 0L))
        assertEquals(listOf(RoutineCue.WALK_IN_TEN), engine.accept(input(time = 5_000L)).cues)
        assertEquals(emptyList<RoutineCue>(), engine.accept(input(time = 5_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 12_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 13_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 14_000L)).cues)
        assertEquals(emptyList<RoutineCue>(), engine.accept(input(time = 14_000L)).cues)
    }

    @Test
    fun shortIntervalsOmitTenSecondWarning() {
        val engine = RoutineEngine(preferences(runSeconds = 10))

        engine.accept(input(time = 0L))
        assertEquals(emptyList<RoutineCue>(), engine.accept(input(time = 1_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 7_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 8_000L)).cues)
        assertEquals(listOf(RoutineCue.COUNTDOWN_BEEP), engine.accept(input(time = 9_000L)).cues)
    }

    @Test
    fun walkCanReuseConstantOrUseDedicatedRange() {
        val sharedWalk = RoutineEngine(preferences(runSeconds = 5, walkUsesConstantRange = true))
        val dedicatedWalk = RoutineEngine(preferences(runSeconds = 5, walkUsesConstantRange = false))

        sharedWalk.accept(input(time = 0L))
        dedicatedWalk.accept(input(time = 0L))

        assertEquals(SpeedRange(7f, 8f), sharedWalk.accept(input(time = 5_000L)).activeRange)
        assertEquals(SpeedRange(4f, 5f), dedicatedWalk.accept(input(time = 5_000L)).activeRange)
    }

    @Test
    fun unlimitedRoutineNeverEntersConstantAfter() {
        val engine = RoutineEngine(preferences(runSeconds = 5, walkSeconds = 5, unlimited = true))

        engine.accept(input(time = 0L))
        val result = engine.accept(input(time = 25_000L, distance = 10_000.0))

        assertEquals(RoutinePhase.WALK, result.phase)
        assertEquals(listOf(RoutineCue.WALK_ROUTINE), result.cues)
        assertEquals(10_000.0, result.routineDistanceMeters, .001)
    }

    @Test
    fun limitedRoutineEndsImmediatelyMidInterval() {
        val engine = RoutineEngine(preferences(unlimited = false, routineKm = .01f))

        engine.accept(input(time = 0L))
        val result = engine.accept(input(time = 1_000L, distance = 10.0))

        assertEquals(RoutinePhase.CONSTANT_AFTER, result.phase)
        assertNull(result.intervalRemainingMillis)
        assertEquals(10.0, result.routineDistanceMeters, .001)
        assertEquals(listOf(RoutineCue.ROUTINE_END), result.cues)
        assertTrue(result.profileChanged)
    }

    @Test
    fun weakGpsAdvancesTimerButNotDistance() {
        val engine = RoutineEngine(preferences(runSeconds = 5))

        engine.accept(input(time = 0L))
        val result = engine.accept(input(time = 5_000L, distance = 10.0, speed = 8f, gpsReliable = false))

        assertEquals(RoutinePhase.WALK, result.phase)
        assertEquals(listOf(RoutineCue.WALK_ROUTINE), result.cues)
        assertEquals(0.0, result.totalDistanceMeters, .001)
        assertEquals(0.0, result.routineDistanceMeters, .001)
    }

    @Test
    fun transitionAddsSpeedUpOrSlowDownAfterPhaseCue() {
        val engine = RoutineEngine(preferences(runSeconds = 5))

        assertEquals(
            listOf(RoutineCue.ROUTINE_START, RoutineCue.SPEED_UP),
            engine.accept(input(time = 0L, speed = 8f)).cues,
        )
        assertEquals(
            listOf(RoutineCue.WALK_ROUTINE, RoutineCue.SLOW_DOWN),
            engine.accept(input(time = 5_000L, speed = 6f)).cues,
        )
    }

    @Test
    fun largeTimeJumpEmitsOnlyCurrentTransitionStateWithoutCueStorm() {
        val engine = RoutineEngine(preferences(runSeconds = 10, walkSeconds = 10))

        engine.accept(input(time = 0L))
        val result = engine.accept(input(time = 55_000L))

        assertEquals(RoutinePhase.WALK, result.phase)
        assertEquals(5_000L, result.intervalRemainingMillis)
        assertEquals(listOf(RoutineCue.WALK_ROUTINE), result.cues)
    }

    private fun preferences(
        warmupKm: Float = 0f,
        runSeconds: Int = 20,
        walkSeconds: Int = 30,
        walkUsesConstantRange: Boolean = false,
        unlimited: Boolean = true,
        routineKm: Float = 5f,
    ) = RunPreferences(
        mode = RoutineMode.WALK_RUN,
        constantRange = SpeedRange(7f, 8f),
        runRange = SpeedRange(9f, 10f),
        walkUsesConstantRange = walkUsesConstantRange,
        walkRange = SpeedRange(4f, 5f),
        initialConstantKm = warmupKm,
        runSeconds = runSeconds,
        walkSeconds = walkSeconds,
        routineUnlimited = unlimited,
        routineKm = routineKm,
    )

    private fun input(
        time: Long,
        distance: Double = 0.0,
        speed: Float? = null,
        gpsReliable: Boolean = true,
    ) = RoutineInput(time, distance, speed, gpsReliable)
}
