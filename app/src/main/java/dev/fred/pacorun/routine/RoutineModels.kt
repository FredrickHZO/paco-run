package dev.fred.pacorun.routine

import dev.fred.pacorun.settings.SpeedRange

enum class RoutinePhase { CONSTANT, CONSTANT_BEFORE, RUN, WALK }

enum class RoutineCue {
    ROUTINE_START, RUN_IN_TEN, WALK_IN_TEN,
    COUNTDOWN_BEEP, RUN_ROUTINE, WALK_ROUTINE,
    SPEED_UP, SLOW_DOWN,
}

data class RoutineInput(
    val elapsedRealtimeMillis: Long,
    val reliableDistanceDeltaMeters: Double,
    val currentReliableSpeedKmh: Float?,
    val gpsReliable: Boolean,
)

data class RoutineResult(
    val phase: RoutinePhase,
    val activeRange: SpeedRange,
    val intervalRemainingMillis: Long?,
    val totalDistanceMeters: Double,
    val routineDistanceMeters: Double,
    val cues: List<RoutineCue>,
    val profileChanged: Boolean,
)
