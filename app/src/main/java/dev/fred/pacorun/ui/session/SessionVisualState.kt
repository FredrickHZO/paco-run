package dev.fred.pacorun.ui.session

import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import java.util.Locale

enum class HeroKind { SPEED, RUN_COUNTDOWN, WALK_COUNTDOWN }

fun heroKind(phase: RoutinePhase): HeroKind = when (phase) {
    RoutinePhase.RUN -> HeroKind.RUN_COUNTDOWN
    RoutinePhase.WALK -> HeroKind.WALK_COUNTDOWN
    RoutinePhase.CONSTANT, RoutinePhase.CONSTANT_BEFORE -> HeroKind.SPEED
}

private const val URGENCY_WINDOW_MILLIS = 10_000L

/** 0 outside the last [URGENCY_WINDOW_MILLIS] of an interval, ramping linearly to 1 at zero remaining. */
fun urgency(remainingMillis: Long?): Float {
    if (remainingMillis == null || remainingMillis >= URGENCY_WINDOW_MILLIS) return 0f
    if (remainingMillis <= 0L) return 1f
    return (URGENCY_WINDOW_MILLIS - remainingMillis) / URGENCY_WINDOW_MILLIS.toFloat()
}

fun paceLabel(state: PaceState): String = when (state) {
    PaceState.ACQUIRING_GPS -> "Acquisizione GPS"
    PaceState.GPS_WEAK -> "GPS debole"
    PaceState.TOO_SLOW -> "Troppo lento"
    PaceState.GOOD -> "Buon passo"
    PaceState.TOO_FAST -> "Troppo veloce"
}

fun countdownText(remainingMillis: Long): String {
    val seconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
