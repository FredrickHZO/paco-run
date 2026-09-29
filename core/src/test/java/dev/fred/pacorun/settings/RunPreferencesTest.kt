package dev.fred.pacorun.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPreferencesTest {
    @Test
    fun routineDefaultsAreUsable() {
        val p = RunPreferences()
        assertEquals(RoutineMode.CONSTANT, p.mode)
        assertEquals(SpeedRange(7.5f, 8.5f), p.constantRange)
        assertTrue(p.walkUsesConstantRange)
        assertEquals(60, p.runSeconds)
        assertEquals(120, p.walkSeconds)
        assertEquals(0f, p.initialConstantKm)
        assertTrue(p.routineUnlimited)
        assertTrue(p.isValid)
    }

    @Test
    fun walkRunRequiresEveryActiveRangeAndPositiveDurations() {
        val base = RunPreferences(mode = RoutineMode.WALK_RUN, routineUnlimited = false, routineKm = 5f)
        assertFalse(base.copy(runSeconds = 0).isValid)
        assertFalse(base.copy(walkSeconds = 0).isValid)
        assertFalse(base.copy(routineKm = 0f).isValid)
        assertFalse(base.copy(walkUsesConstantRange = false, walkRange = SpeedRange(6f, 6f)).isValid)
    }

    @Test
    fun constantRangeMustBeFiniteAndOrdered() {
        assertFalse(RunPreferences(constantRange = SpeedRange(8f, 8f)).isValid)
        assertFalse(RunPreferences(constantRange = SpeedRange(Float.NaN, 8f)).isValid)
        assertFalse(RunPreferences(constantRange = SpeedRange(7f, Float.POSITIVE_INFINITY)).isValid)
        assertTrue(RunPreferences(constantRange = SpeedRange(7f, 8f)).isValid)
    }

    @Test
    fun walkRunInitialDistanceMustBeFiniteAndNonNegative() {
        val base = RunPreferences(mode = RoutineMode.WALK_RUN)
        assertFalse(base.copy(initialConstantKm = Float.NaN).isValid)
        assertFalse(base.copy(initialConstantKm = Float.POSITIVE_INFINITY).isValid)
        assertFalse(base.copy(initialConstantKm = Float.NEGATIVE_INFINITY).isValid)
        assertFalse(base.copy(initialConstantKm = -0.1f).isValid)
    }

    @Test
    fun cueVolumeMustBeFiniteAndWithinRange() {
        assertFalse(RunPreferences(cueVolume = Float.NaN).isValid)
        assertFalse(RunPreferences(cueVolume = Float.POSITIVE_INFINITY).isValid)
        assertFalse(RunPreferences(cueVolume = -0.1f).isValid)
        assertFalse(RunPreferences(cueVolume = 1.1f).isValid)
        assertTrue(RunPreferences(cueVolume = 0f).isValid)
        assertTrue(RunPreferences(cueVolume = 1f).isValid)
    }
}
