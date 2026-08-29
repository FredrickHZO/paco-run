package dev.fred.pacorun.settings

enum class RoutineMode { CONSTANT, WALK_RUN }

data class SpeedRange(val minKmh: Float, val maxKmh: Float) {
    val isValid: Boolean
        get() = minKmh.isFinite() && maxKmh.isFinite() && minKmh < maxKmh
}

data class RunPreferences(
    val mode: RoutineMode = RoutineMode.CONSTANT,
    val constantRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val runRange: SpeedRange = SpeedRange(7.5f, 8.5f),
    val walkUsesConstantRange: Boolean = true,
    val walkRange: SpeedRange = SpeedRange(4f, 6f),
    val initialConstantKm: Float = 0f,
    val runSeconds: Int = 60,
    val walkSeconds: Int = 120,
    val routineUnlimited: Boolean = true,
    val routineKm: Float = 5f,
    val confirmationSamples: Int = 2,
    val repeatMillis: Long = 5_000L,
    val gpsCuesEnabled: Boolean = false,
    val cueVolume: Float = .6f,
    val dynamicColor: Boolean = true,
) {
    val isValid: Boolean
        get() {
            if (!constantRange.isValid) return false
            if (mode != RoutineMode.WALK_RUN) return true

            val effectiveWalkRange = if (walkUsesConstantRange) constantRange else walkRange
            return runRange.isValid &&
                effectiveWalkRange.isValid &&
                initialConstantKm >= 0f &&
                runSeconds > 0 &&
                walkSeconds > 0 &&
                (routineUnlimited || (routineKm.isFinite() && routineKm > 0f))
        }
}
