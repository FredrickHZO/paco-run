package dev.fred.pacorun.pace

import dev.fred.pacorun.settings.RunPreferences

enum class PaceState { ACQUIRING_GPS, GPS_WEAK, TOO_SLOW, GOOD, TOO_FAST }

enum class Cue { TOO_SLOW, TOO_FAST, GOOD_PACE, BAD_GPS, GOOD_GPS }

data class PaceConfig(
    val minKmh: Float,
    val maxKmh: Float,
    val confirmationSamples: Int,
    val repeatMillis: Long,
    val gpsCuesEnabled: Boolean,
)

data class PaceResult(
    val state: PaceState,
    val speedKmh: Float?,
    val cue: Cue?,
)

fun RunPreferences.paceConfig() = PaceConfig(
    minKmh = constantRange.minKmh,
    maxKmh = constantRange.maxKmh,
    confirmationSamples = confirmationSamples,
    repeatMillis = repeatMillis,
    gpsCuesEnabled = gpsCuesEnabled,
)
