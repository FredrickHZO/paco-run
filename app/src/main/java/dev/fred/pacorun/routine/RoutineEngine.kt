package dev.fred.pacorun.routine

import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange

class RoutineEngine(private val preferences: RunPreferences) {
    private var phase = if (preferences.mode == RoutineMode.CONSTANT) RoutinePhase.CONSTANT else RoutinePhase.CONSTANT_BEFORE
    private var initialized = false
    private var intervalStartedAtMillis = 0L
    private var firstRun = true
    private var warningEmitted = false
    private val countdownSecondsEmitted = mutableSetOf<Int>()
    private var totalDistanceMeters = 0.0
    private var routineDistanceMeters = 0.0
    private var lastElapsedRealtimeMillis = 0L
    private var lastTimingCheckMillis = 0L

    fun accept(input: RoutineInput): RoutineResult {
        lastElapsedRealtimeMillis = input.elapsedRealtimeMillis
        val distanceDelta = input.reliableDistanceDeltaMeters.takeIf {
            input.gpsReliable && it.isFinite() && it > 0.0
        } ?: 0.0
        totalDistanceMeters += distanceDelta
        if (phase == RoutinePhase.RUN || phase == RoutinePhase.WALK) routineDistanceMeters += distanceDelta

        if (preferences.mode == RoutineMode.CONSTANT) return result(emptyList(), profileChanged = false)

        val cues = mutableListOf<RoutineCue>()
        var profileChanged = false
        if (!initialized) {
            initialized = true
            if (preferences.initialConstantKm == 0f) {
                startRun(input.elapsedRealtimeMillis, reliableSpeed(input), cues)
                profileChanged = true
            }
        }

        if (phase == RoutinePhase.CONSTANT_BEFORE && totalDistanceMeters >= kmToMeters(preferences.initialConstantKm)) {
            startRun(input.elapsedRealtimeMillis, reliableSpeed(input), cues)
            profileChanged = true
        }

        if (phase == RoutinePhase.RUN || phase == RoutinePhase.WALK) {
            if (!preferences.routineUnlimited && routineDistanceMeters >= kmToMeters(preferences.routineKm)) {
                phase = RoutinePhase.CONSTANT_AFTER
                cues += RoutineCue.ROUTINE_END
                directionCue(reliableSpeed(input), activeRange())?.let(cues::add)
                profileChanged = true
            } else {
                val transitions = advanceTo(input.elapsedRealtimeMillis)
                if (transitions > 0) {
                    cues += phaseCue()
                    directionCue(reliableSpeed(input), activeRange())?.let(cues::add)
                    profileChanged = true
                    if (transitions == 1 && durationMillis() <= 3_000L) cues += timingCues(input.elapsedRealtimeMillis)
                    else lastTimingCheckMillis = input.elapsedRealtimeMillis
                } else {
                    cues += timingCues(input.elapsedRealtimeMillis)
                }
            }
        }

        return result(cues, profileChanged)
    }

    private fun startRun(now: Long, speed: Float?, cues: MutableList<RoutineCue>) {
        phase = RoutinePhase.RUN
        intervalStartedAtMillis = now
        resetIntervalCues()
        cues += phaseCue()
        directionCue(speed, activeRange())?.let(cues::add)
    }

    private fun advanceTo(now: Long): Int {
        val elapsed = now - intervalStartedAtMillis
        if (elapsed < durationMillis()) return 0

        var remaining = elapsed - durationMillis()
        intervalStartedAtMillis += durationMillis()
        phase = nextPhase()
        val runMillis = preferences.runSeconds * 1_000L
        val walkMillis = preferences.walkSeconds * 1_000L
        val cycleMillis = runMillis + walkMillis
        val fullCycles = remaining / cycleMillis
        intervalStartedAtMillis += fullCycles * cycleMillis
        remaining -= fullCycles * cycleMillis

        var transitions = if (fullCycles > 0L) 2 else 1
        if (phase == RoutinePhase.RUN && remaining >= runMillis) {
            phase = RoutinePhase.WALK
            intervalStartedAtMillis += runMillis
            transitions = 2
        } else if (phase == RoutinePhase.WALK && remaining >= walkMillis) {
            phase = RoutinePhase.RUN
            intervalStartedAtMillis += walkMillis
            transitions = 2
        }
        resetIntervalCues()
        return transitions
    }

    private fun timingCues(now: Long): List<RoutineCue> {
        val previousElapsed = lastTimingCheckMillis - intervalStartedAtMillis
        val elapsed = now - intervalStartedAtMillis
        lastTimingCheckMillis = now
        if (elapsed !in 0 until durationMillis()) return emptyList()
        return buildList {
            val currentBeep = (1..3).firstOrNull { seconds ->
                val beepAt = durationMillis() - seconds * 1_000L
                beepAt >= 0L && previousElapsed < beepAt && elapsed >= beepAt && seconds !in countdownSecondsEmitted
            }
            if (currentBeep != null) {
                countdownSecondsEmitted += currentBeep
                add(RoutineCue.COUNTDOWN_BEEP)
                return@buildList
            }
            val warningAt = durationMillis() - 10_000L
            val remaining = durationMillis() - elapsed
            if (
                durationMillis() > 10_000L &&
                    previousElapsed < warningAt &&
                    elapsed >= warningAt &&
                    remaining in 9_001L..10_000L &&
                    !warningEmitted
            ) {
                warningEmitted = true
                add(if (phase == RoutinePhase.RUN) RoutineCue.WALK_IN_TEN else RoutineCue.RUN_IN_TEN)
            }
        }
    }

    private fun phaseCue() = when (phase) {
        RoutinePhase.RUN -> if (firstRun) RoutineCue.ROUTINE_START.also { firstRun = false } else RoutineCue.RUN_ROUTINE
        RoutinePhase.WALK -> RoutineCue.WALK_ROUTINE
        else -> error("A phase cue is only valid for Run or Walk")
    }

    private fun directionCue(speed: Float?, range: SpeedRange): RoutineCue? = when {
        speed == null || !speed.isFinite() -> null
        speed < range.minKmh -> RoutineCue.SPEED_UP
        speed > range.maxKmh -> RoutineCue.SLOW_DOWN
        else -> null
    }

    private fun reliableSpeed(input: RoutineInput) = input.currentReliableSpeedKmh.takeIf { input.gpsReliable }

    private fun nextPhase() = if (phase == RoutinePhase.RUN) RoutinePhase.WALK else RoutinePhase.RUN

    private fun durationMillis() = when (phase) {
        RoutinePhase.RUN -> preferences.runSeconds * 1_000L
        RoutinePhase.WALK -> preferences.walkSeconds * 1_000L
        else -> 0L
    }

    private fun activeRange() = when (phase) {
        RoutinePhase.RUN -> preferences.runRange
        RoutinePhase.WALK -> if (preferences.walkUsesConstantRange) preferences.constantRange else preferences.walkRange
        else -> preferences.constantRange
    }

    private fun resetIntervalCues() {
        warningEmitted = false
        countdownSecondsEmitted.clear()
        lastTimingCheckMillis = intervalStartedAtMillis - 1L
    }

    private fun kmToMeters(km: Float) = km.toString().toDouble() * 1_000.0

    private fun result(cues: List<RoutineCue>, profileChanged: Boolean) = RoutineResult(
        phase = phase,
        activeRange = activeRange(),
        intervalRemainingMillis = when (phase) {
            RoutinePhase.RUN, RoutinePhase.WALK ->
                (durationMillis() - (lastElapsedRealtimeMillis - intervalStartedAtMillis)).coerceAtLeast(0L)
            else -> null
        },
        totalDistanceMeters = totalDistanceMeters,
        routineDistanceMeters = routineDistanceMeters,
        cues = cues,
        profileChanged = profileChanged,
    )
}
