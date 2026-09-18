package dev.fred.pacorun.ui.session

import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionVisualStateTest {
    @Test
    fun runAndWalkPhasesUseCountdownHero() {
        assertEquals(HeroKind.RUN_COUNTDOWN, heroKind(RoutinePhase.RUN))
        assertEquals(HeroKind.WALK_COUNTDOWN, heroKind(RoutinePhase.WALK))
    }

    @Test
    fun constantPhasesUseSpeedHero() {
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT))
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT_BEFORE))
        assertEquals(HeroKind.SPEED, heroKind(RoutinePhase.CONSTANT_AFTER))
    }

    @Test
    fun urgencyIsZeroOutsideTheLastTenSeconds() {
        assertEquals(0f, urgency(null), 0f)
        assertEquals(0f, urgency(10_000L), 0f)
        assertEquals(0f, urgency(20_000L), 0f)
    }

    @Test
    fun urgencyRampsLinearlyToOneAtZeroRemaining() {
        assertEquals(0.5f, urgency(5_000L), 0.001f)
        assertEquals(1f, urgency(0L), 0f)
        assertEquals(1f, urgency(-500L), 0f)
    }

    @Test
    fun paceLabelsAreItalianAndCoverEveryState() {
        assertEquals("Acquisizione GPS", paceLabel(PaceState.ACQUIRING_GPS))
        assertEquals("GPS debole", paceLabel(PaceState.GPS_WEAK))
        assertEquals("Troppo lento", paceLabel(PaceState.TOO_SLOW))
        assertEquals("Buon passo", paceLabel(PaceState.GOOD))
        assertEquals("Troppo veloce", paceLabel(PaceState.TOO_FAST))
    }

    @Test
    fun countdownTextRoundsUpToTheNextSecond() {
        assertEquals("0:45", countdownText(45_000L))
        assertEquals("0:01", countdownText(1L))
        assertEquals("1:00", countdownText(59_001L))
        assertEquals("0:00", countdownText(0L))
    }
}
